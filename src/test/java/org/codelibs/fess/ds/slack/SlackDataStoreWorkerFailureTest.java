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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.slack.api.SlackApiException;
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
 * Covers what a message's worker task does with a failure that is <em>not</em> a
 * {@link SlackApiException} (#40).
 *
 * <p>
 * {@code processChannelMessages} dispatches each message to the executor inside a task whose only
 * catch used to be {@link SlackApiException}. {@code processMessage} carries its own
 * {@code catch (Throwable)} and so was covered, but {@code processMessageReplies} is not: it walks
 * {@code conversations.replies} on that same worker thread, and that walk can raise a
 * {@link SlackDataStoreException} from {@code Request#parseResponse} when the response body cannot
 * be parsed -- an intermediary answering with an HTML error page is the everyday trigger -- as well
 * as any runtime failure from the HTTP layer.
 * </p>
 *
 * <p>
 * Anything but a {@link SlackApiException} therefore escaped the {@code Runnable}, and
 * {@code ThreadPoolExecutor#execute} hands an escaped throwable to the thread's default uncaught
 * exception handler: it never reached the application logger, never failed the crawl, and was never
 * recorded against the document. The thread's replies were simply missing from the index while the
 * job reported success -- the silent partial indexing this class pins shut.
 * </p>
 */
public class SlackDataStoreWorkerFailureTest extends UnitDsTestCase {

    /** An HTML error page, the everyday shape of a body {@code Request#parseResponse} cannot read. */
    private static final String HTML_ERROR_PAGE = "<html><head><title>502 Bad Gateway</title></head><body>502</body></html>";

    private static final String PARENT_TS = "1111111111.000100";

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    private List<FailureRecord> failures;

    /** One {@code FailureUrlService#store} call, captured for assertions. */
    private record FailureRecord(String errorName, String url, Throwable e) {
    }

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

        // Recording, not a no-op stub: what this class asserts is precisely that the failure
        // reaches this service. The list is concurrent because store() is called from whichever
        // worker thread the executor dispatched the message to.
        failures = new CopyOnWriteArrayList<>();
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                failures.add(new FailureRecord(errorName, url, e));
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
     * One channel holding one thread parent ({@code thread_ts} equal to its own {@code ts}, see
     * {@code SlackDataStore#isThreadParent}), whose {@code conversations.replies} walk answers with
     * a body no response class can parse.
     */
    private void enqueueThreadWithUnparseableReplies() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"parent\",\"ts\":\"" + PARENT_TS + "\",\"thread_ts\":\""
                        + PARENT_TS + "\",\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"}],"
                        + "\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json(HTML_ERROR_PAGE));
    }

    private DataStoreParams newParamMap() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        return paramMap;
    }

    @Test
    public void test_unparseableRepliesBody_recordsTheThreadAsAFailure() {
        enqueueThreadWithUnparseableReplies();

        dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), newParamMap(), new HashMap<>(), new HashMap<>());

        assertEquals("the failed reply walk is recorded exactly once", 1, failures.size());
        final FailureRecord failure = failures.get(0);
        assertEquals(SlackDataStoreException.class.getCanonicalName(), failure.errorName());
        // A thread has no permalink of its own, and resolving the parent's can itself call
        // chat.getPermalink -- not something to do from an error handler -- so this is the same
        // channel/timestamp identifier processMessage falls back to when a permalink is missing.
        assertEquals("C1/" + PARENT_TS, failure.url());
    }

    @Test
    public void test_unparseableRepliesBody_logsAWarningNamingTheThread() {
        enqueueThreadWithUnparseableReplies();

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), newParamMap(), new HashMap<>(), new HashMap<>());
            assertTrue("the failure reaches the application logger, not the default uncaught-exception handler",
                    appender.messagesAt(Level.WARN).stream().anyMatch(m -> m.contains("C1/" + PARENT_TS)));
        } finally {
            appender.detach();
        }
    }

    /**
     * Regression guard, not a red test: this passed before #40 was fixed too, because the escaped
     * throwable was silently discarded. It pins the other half of the fix -- that widening the
     * catch records the failure without promoting it to a fatal one. A non-Slack failure on one
     * thread must not abort the crawl the way {@code latchFatalError} does, so the parent message
     * that was already indexed stays indexed, the walk continues to the next message, and
     * {@code storeData} returns normally.
     */
    @Test
    public void test_unparseableRepliesBody_doesNotAbortTheCrawl() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[" + "{\"text\":\"parent\",\"ts\":\"" + PARENT_TS + "\",\"thread_ts\":\""
                        + PARENT_TS + "\",\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"},"
                        + "{\"text\":\"later\",\"ts\":\"1111111111.000200\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000200\"}"
                        + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json(HTML_ERROR_PAGE));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, newParamMap(), new HashMap<>(), new HashMap<>());

        assertEquals("both messages are still indexed", 2, callback.getDataMaps().size());
    }
}
