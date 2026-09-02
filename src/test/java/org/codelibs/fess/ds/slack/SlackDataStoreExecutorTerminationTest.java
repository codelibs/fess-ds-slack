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
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
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
 * Covers C11: {@code storeData} used to discard {@code awaitTermination}'s boolean return and
 * call {@code shutdownNow()} unconditionally in {@code finally}, so a crawl whose queued work
 * outlived the wait silently dropped its tail and still reported success. This drives that path
 * deterministically with {@code executor_timeout=0} against a callback that always takes longer
 * than zero seconds, instead of waiting on the real 60-second default.
 */
public class SlackDataStoreExecutorTerminationTest extends UnitDsTestCase {

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

        // The error path resolves this via ComponentUtil.getComponent(FailureUrlService.class);
        // this test's callback never throws, so a well-formed run never reaches it, but a
        // mapping mistake would otherwise fail with a ComponentNotFoundException instead of a
        // legible assertion failure.
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
    public void test_awaitTerminationTimesOut_logsWarning() throws InterruptedException {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"hi\",\"ts\":\"1.0\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1\"}],\"has_more\":false,"
                        + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        paramMap.put("executor_timeout", "0");

        final IndexUpdateCallback slowCallback = new IndexUpdateCallback() {
            @Override
            public void store(final DataStoreParams p, final Map<String, Object> dataMap) {
                try {
                    Thread.sleep(300L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public long getExecuteTime() {
                return 0;
            }

            @Override
            public long getDocumentSize() {
                return 0;
            }

            @Override
            public void commit() {
                // nothing to do
            }
        };

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), slowCallback, paramMap, new HashMap<>(), new HashMap<>());
            // Asserted on message content, not merely "some WARN happened": this container does
            // not register a UrlFilter component either, and storeData already warns about that
            // unconditionally, which would otherwise make this assertion a false positive both
            // before and after the fix.
            assertTrue("must warn specifically about the executor not terminating in time",
                    appender.messagesAt(Level.WARN).stream().anyMatch(m -> m.toLowerCase().contains("terminat")));
        } finally {
            // The interrupted worker thread (shutdownNow(), above) needs a moment to unwind
            // after its Thread.sleep throws. Without waiting, its tail can run after this
            // method returns and race this class's own tearDown/the next test's setUp for
            // ComponentUtil's component registrations -- observed as a stray
            // AutoBindingFailureException logged from a pool thread with no effect on this
            // test's own result, but noisy and liable to depend on unrelated test ordering.
            Thread.sleep(500L);
            appender.detach();
        }
    }
}
