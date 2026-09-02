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
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.codelibs.core.exception.InterruptedRuntimeException;
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
 * Covers a Minor finding from the Phase 2 whole-branch review: {@code storeData}'s {@code catch
 * (final InterruptedException e)} around {@code executorService.awaitTermination} did not
 * restore the interrupt flag (unlike {@code Request#sleepBeforeRetry}, which does), and silently
 * discarded any {@link SlackApiException} already latched by a worker thread in favor of {@link
 * InterruptedRuntimeException}.
 *
 * <p>
 * Both scenarios replace the executor with one whose {@code awaitTermination} throws {@link
 * InterruptedException} immediately, so this is deterministic and does not depend on winning a
 * real race against a background thread.
 * </p>
 */
public class SlackDataStoreInterruptedAwaitTerminationTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    /** An {@link ExecutorService} that delegates everything except {@code awaitTermination}, which always throws. */
    private static ExecutorService newInterruptingExecutor() {
        final ExecutorService delegate = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        return new AbstractExecutorService() {
            @Override
            public void execute(final Runnable command) {
                delegate.execute(command);
            }

            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("simulated interrupt for test");
            }
        };
    }

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
        // Defensive: clear the interrupt flag this test deliberately sets on the test thread, so
        // it cannot leak into a later test running on the same thread.
        Thread.interrupted();
        super.tearDown(testInfo);
    }

    @Test
    public void test_interruptedAwaitTermination_restoresInterruptFlag() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected ExecutorService newFixedThreadPool(final int nThreads) {
                return newInterruptingExecutor();
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        Assertions.assertThrows(InterruptedRuntimeException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

        assertTrue("the interrupt flag must be restored, matching Request.sleepBeforeRetry", Thread.currentThread().isInterrupted());
    }

    @Test
    public void test_interruptedAwaitTermination_prefersAlreadyLatchedFatalError() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected ExecutorService newFixedThreadPool(final int nThreads) {
                return newInterruptingExecutor();
            }

            @Override
            protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
                    final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
                    final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client,
                    final Team team, final Channel channel, final AtomicReference<SlackApiException> fatalError, final List<String> roles) {
                // Simulate a fatal error already latched by a worker thread before storeData
                // reaches the (also interrupted) awaitTermination call below.
                latchFatalError(executorService, fatalError, (AtomicBoolean) configMap.get(CRAWL_ALIVE),
                        new SlackApiException("conversations.replies", "missing_scope"));
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("a fatal error latched before the interrupt must win over InterruptedRuntimeException", "missing_scope",
                exception.getErrorCode());
        assertTrue("the interrupt flag must still be restored even on this path", Thread.currentThread().isInterrupted());
    }
}
