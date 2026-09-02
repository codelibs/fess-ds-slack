/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.slack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers Important-1 from the Phase 2 whole-branch review: {@code latchFatalError} used to call
 * only {@code executorService.shutdownNow()}, so the main thread's channel-dispatch loop in
 * {@code storeData} -- {@code client.getChannels(channel -> ...)} -- never learned that a fatal
 * error had been latched on a worker thread, and kept walking and dispatching every remaining
 * channel (each one sleeping {@code read_interval} along the way) before {@code storeData}
 * finally reported the failure. This matters most for {@code missing_scope}, which is scoped per
 * Slack Web API method: a token missing only the scope {@code conversations.replies} needs keeps
 * succeeding on every main-thread {@code conversations.history} call indefinitely, unlike {@code
 * invalid_auth}, which self-corrects within roughly a page because the very next main-thread call
 * fails the same way.
 *
 * <p>
 * Drives {@code latchFatalError} directly from an overridden {@code processChannelMessages} (the
 * same technique {@link SlackDataStoreStopTest} uses for an operator-initiated stop), so the
 * effect on the main-thread dispatch loop is asserted deterministically instead of racing a real
 * worker thread. This fails against the pre-fix code: without {@code stop()} in {@code
 * latchFatalError}, {@code alive} never flips, and the second channel is dispatched too.
 * </p>
 */
public class SlackDataStoreFatalLatchStopTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();

        ComponentUtil.register(new SystemHelper(), "systemHelper");
        final CrawlerStatsHelper crawlerStatsHelper = new CrawlerStatsHelper();
        crawlerStatsHelper.init();
        ComponentUtil.register(crawlerStatsHelper, "crawlerStatsHelper");
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    @Test
    public void test_latchFatalError_alsoStopsMainThreadChannelDispatch() {
        server.enqueue("/api/conversations.list", SlackApiMockServer.json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"},"
                + "{\"id\":\"C2\",\"name\":\"random\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<String> processedChannelIds = new ArrayList<>();
        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
                    final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
                    final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client,
                    final Team team, final Channel channel, final AtomicReference<SlackApiException> fatalError, final List<String> roles) {
                processedChannelIds.add(channel.getId());
                // Simulate a fatal SlackApiException latched by a worker thread while processing
                // this channel -- e.g. missing_scope discovered by conversations.replies -- which
                // in production happens asynchronously, off the thread running this loop.
                latchFatalError(executorService, fatalError, (AtomicBoolean) configMap.get(CRAWL_ALIVE),
                        new SlackApiException("conversations.replies", "missing_scope"));
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "*all");

        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("missing_scope", exception.getErrorCode());
        assertEquals("only the channel being processed when the fatal error latched may be dispatched", 1, processedChannelIds.size());
        assertEquals("C1", processedChannelIds.get(0));
    }

    /**
     * The regression that matters most here: a latched fatal error must not outlive the crawl
     * that produced it.
     *
     * <p>
     * {@code latchFatalError} used to call {@code stop()}, which flips the inherited
     * {@code AbstractDataStore#alive} flag. Fess assigns that flag {@code true} exactly once, at
     * field initialisation, and never resets it -- and a data store is a LastaDi singleton that
     * {@code DataStoreFactory} hands to every crawl for the lifetime of the JVM. One
     * rate-limited {@code conversations.replies} exhausting its retries was therefore enough to
     * leave every later crawl of every Slack {@code DataConfig} walking zero channels, indexing
     * nothing, and still returning normally so the job reported success. The documents already
     * indexed are not removed -- {@code DataIndexHelper.deleteOldDocs} skips anything carrying
     * {@code expires}, which {@code AbstractDataStore} stamps on every document whenever {@code
     * day.for.cleanup} is non-negative (default 3) -- so the damage is a silently stale index
     * that no longer picks up new Slack content, with no error anywhere to say so.
     * </p>
     */
    @Test
    public void test_aLatchedFatalErrorDoesNotOutliveItsOwnCrawl() {
        final List<String> firstCrawl = new ArrayList<>();
        final List<String> secondCrawl = new ArrayList<>();
        final AtomicBoolean latchOnThisCrawl = new AtomicBoolean(true);

        final RecordingSlackDataStore dataStore = new RecordingSlackDataStore(firstCrawl, secondCrawl, latchOnThisCrawl);

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "*all");

        // Crawl 1 -- a worker latches a fatal error while the first channel is being processed.
        server.enqueue("/api/conversations.list", twoChannels());
        Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));
        assertEquals("the fatal error stops this crawl's walk", 1, firstCrawl.size());
        assertTrue("the shared, never-reset alive flag must survive a fatal error", dataStore.isAlive());

        // Crawl 2 -- same data store instance, healthy workspace, valid token.
        latchOnThisCrawl.set(false);
        server.enqueue("/api/conversations.list", twoChannels());
        dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());
        assertEquals("a later crawl on the same instance must walk every channel again", 2, secondCrawl.size());
    }

    private SlackApiMockServer.MockResponse twoChannels() {
        return SlackApiMockServer.json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"},"
                + "{\"id\":\"C2\",\"name\":\"random\"}],\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    /**
     * Records the channels each crawl dispatches and, on the first crawl only, latches a fatal
     * error the way a worker thread would. Also exposes the inherited {@code alive} flag.
     */
    private static final class RecordingSlackDataStore extends SlackDataStore {
        private final List<String> firstCrawl;
        private final List<String> secondCrawl;
        private final AtomicBoolean latchOnThisCrawl;

        RecordingSlackDataStore(final List<String> firstCrawl, final List<String> secondCrawl, final AtomicBoolean latchOnThisCrawl) {
            this.firstCrawl = firstCrawl;
            this.secondCrawl = secondCrawl;
            this.latchOnThisCrawl = latchOnThisCrawl;
        }

        boolean isAlive() {
            return alive;
        }

        @Override
        protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
                final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
                final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client, final Team team,
                final Channel channel, final AtomicReference<SlackApiException> fatalError, final List<String> roles) {
            if (latchOnThisCrawl.get()) {
                firstCrawl.add(channel.getId());
                latchFatalError(executorService, fatalError, (AtomicBoolean) configMap.get(CRAWL_ALIVE),
                        new SlackApiException("conversations.replies", "missing_scope"));
            } else {
                secondCrawl.add(channel.getId());
            }
        }
    }
}
