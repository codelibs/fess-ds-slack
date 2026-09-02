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

import java.util.HashMap;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.slack.api.SlackApiException;
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
 * Covers the Critical finding from the Phase 2 whole-branch review: once {@code
 * conversations.list} exhausts its retries during {@link SlackClient}'s constructor preload,
 * {@code preloadedChannels} used to stay empty; {@code SlackClient#getAllChannels(java.util.function.Consumer)}
 * then fell through to a second {@code conversations.list} call that failed identically; {@code
 * storeData} walked zero channels and returned normally, reporting a false "success" -- so the
 * index silently went stale, with nothing in the job result to say the crawl had fetched
 * nothing. (The existing documents are not deleted: {@code DataIndexHelper#process()} does run
 * {@code deleteOldDocs()} in its {@code finally} block, but that query excludes any document
 * carrying {@code expires}, which {@code AbstractDataStore} stamps on all of them whenever
 * {@code day.for.cleanup} is non-negative -- it defaults to 3.)
 *
 * <p>
 * This test fails against the pre-fix code: with the old, warn-and-continue treatment of {@code
 * ratelimited}, {@code storeData} returns normally instead of throwing, and
 * {@code Assertions.assertThrows} fails with "no exception was thrown".
 * </p>
 */
public class SlackDataStoreRetryExhaustionTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        dataStore = new SlackDataStore();

        ComponentUtil.register(new SystemHelper(), "systemHelper");
        final CrawlerStatsHelper crawlerStatsHelper = new CrawlerStatsHelper();
        crawlerStatsHelper.init();
        ComponentUtil.register(crawlerStatsHelper, "crawlerStatsHelper");

        // Not expected to be reached: the retry-exhaustion failure propagates as an uncaught
        // SlackApiException raised directly out of the SlackClient constructor, on storeData's
        // own thread, well before any channel/message is ever processed. Registered anyway so a
        // wiring mistake fails with a legible assertion instead of a ComponentNotFoundException.
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
    public void test_conversationsListRetryExhaustion_duringPreload_failsStoreDataInsteadOfWalkingZeroChannels() {
        // max_retry_count=0 means the very first attempt is also the last: no actual retry wait
        // is needed to reach exhaustion, keeping this test fast.
        server.enqueue("/api/conversations.list", SlackApiMockServer.rateLimited(0));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("max_retry_count", "0");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("conversations.list", exception.getMethod());
        assertEquals("ratelimited", exception.getErrorCode());
        assertEquals("no document may be indexed when the channel listing itself failed", 0, callback.size());
    }
}
