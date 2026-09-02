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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers C21 (the {@code SlackDataStore} half): {@code storeData}'s channel-dispatch loop --
 * {@code client.getChannels(channel -> ...)} -- never consulted {@code alive}, so an operator
 * clicking "stop" in the admin UI (which calls {@link SlackDataStore#stop()} via {@code
 * DataIndexHelper}) had no effect on an in-flight Slack crawl. This drives that loop directly,
 * bypassing the real per-message pipeline (see the overridden {@code processChannelMessages}
 * below) so the stop can be triggered deterministically on the same thread that walks channels,
 * instead of racing a worker thread.
 */
public class SlackDataStoreStopTest extends UnitDsTestCase {

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

    /**
     * Two channels are preloaded; the first channel's processing calls {@link
     * SlackDataStore#stop()}, simulating an operator hitting "stop" mid-crawl. The second
     * channel must never be dispatched, {@code storeData} must return normally (not throw), and
     * an INFO log must tell the operator the crawl was stopped early.
     */
    @Test
    public void test_stopDuringChannelDispatch_skipsRemainingChannelsAndLogsInfo() {
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
                // Simulate an operator clicking "stop" in the admin UI while this channel's
                // messages are (notionally) still being processed.
                stop();
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "*all");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

            assertEquals("only the channel being processed when stop() was called may be dispatched", 1, processedChannelIds.size());
            assertEquals("C1", processedChannelIds.get(0));
            assertTrue("must log at info that the crawl was stopped, so an operator sees why the index is short",
                    appender.messagesAt(Level.INFO).stream().anyMatch(m -> m.toLowerCase().contains("stop")));
        } finally {
            appender.detach();
        }
    }
}
