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
 * Covers a gap the coordinator's review found in the C12 fix: a fatal {@link SlackApiException}
 * thrown from {@code conversations.replies} happens inside a worker thread dispatched by
 * {@code processChannelMessages}'s {@code executorService.execute(...)}, not on the thread
 * running {@code storeData} itself. {@code files.list}, {@code conversations.list},
 * {@code conversations.history}, and {@code users.list} all run fatal-safe calls on
 * {@code storeData}'s own thread and already propagate correctly (see
 * {@link SlackClientErrorClassificationTest}); this class is specifically about the one call
 * that does not: an uncaught exception inside {@code ThreadPoolExecutor.execute(...)}'s Runnable
 * does not reach the submitting thread, so without an explicit catch and rethrow, a revoked
 * token discovered while walking thread replies used to leave {@code storeData} returning
 * normally -- reporting success -- while every later reply lookup failed the same way.
 */
public class SlackDataStoreFatalErrorPropagationTest extends UnitDsTestCase {

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

        // Not expected to be reached by this test -- the fatal error propagates as an uncaught
        // SlackApiException, not a CrawlingAccessException recorded via FailureUrlService -- but
        // registered anyway so a wiring mistake fails with a legible assertion instead of a
        // ComponentNotFoundException, matching the other storeData-level tests in this module.
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
     * The message's {@code thread_ts} equals its own {@code ts}, marking it a thread parent (see
     * {@code SlackDataStore#isThreadParent}), so processing it also triggers
     * {@code processMessageReplies} -> {@code conversations.replies} on the same worker thread.
     */
    @Test
    public void test_fatalErrorInConversationsReplies_failsStoreData() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json(
                        "{\"ok\":true,\"messages\":[{\"text\":\"hi\",\"ts\":\"1111111111.000100\"," + "\"thread_ts\":\"1111111111.000100\","
                                + "\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"}],\"has_more\":false,"
                                + "\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("invalid_auth", exception.getErrorCode());
        assertEquals("conversations.replies", exception.getMethod());
    }
}
