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

import org.codelibs.fess.ds.slack.api.RequestContext;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers the "non-numeric falls back to the default, with a warning" contract that
 * {@code connection_timeout}, {@code read_timeout}, {@code max_retry_count}, and
 * {@code retry_interval} all promise. Every other test in this module passes a valid numeric
 * string for these parameters, so nothing else exercises the catch blocks -- a swallowed
 * exception or a broken catch would otherwise ship silently. Also covers the negative-value
 * clamp that all three of {@code retry_interval}, {@code connection_timeout}, and
 * {@code read_timeout} need on top of that, since a negative value is numeric and each has its
 * own way of turning that into a stalled or dead crawl instead of a clean fallback.
 */
public class SlackClientParameterFallbackTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackClient client;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        client = server.newClient("xoxb-test");
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    @Test
    public void test_connectionTimeout_nonNumericFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("connection_timeout", "not-a-number");
        assertEquals(RequestContext.DEFAULT_CONNECTION_TIMEOUT, client.getConnectionTimeout(paramMap));
    }

    @Test
    public void test_readTimeout_nonNumericFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("read_timeout", "not-a-number");
        assertEquals(RequestContext.DEFAULT_READ_TIMEOUT, client.getReadTimeout(paramMap));
    }

    @Test
    public void test_maxRetryCount_nonNumericFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_retry_count", "not-a-number");
        assertEquals(RequestContext.DEFAULT_MAX_RETRY_COUNT, client.getMaxRetryCount(paramMap));
    }

    @Test
    public void test_retryInterval_nonNumericFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("retry_interval", "not-a-number");
        assertEquals(RequestContext.DEFAULT_RETRY_INTERVAL, client.getRetryInterval(paramMap));
    }

    /**
     * A negative value is numeric -- it would pass a plain {@code Long.parseLong} -- but is
     * just as unusable: unclamped, it would reach {@code Thread.sleep} in {@code Request}'s
     * retry loop and throw {@link IllegalArgumentException}, killing the crawl the same way an
     * unhandled non-numeric value would.
     */
    @Test
    public void test_retryInterval_negativeFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("retry_interval", "-1");
        assertEquals(RequestContext.DEFAULT_RETRY_INTERVAL, client.getRetryInterval(paramMap));
    }

    /**
     * curl4j treats a negative timeout as "unset" and reverts to the JDK's blocking-forever
     * default -- silently restoring the exact stall {@code connection_timeout} exists to
     * prevent. It does not throw, unlike a negative {@code retry_interval} reaching
     * {@code Thread.sleep}, so nothing but an explicit clamp catches this.
     */
    @Test
    public void test_connectionTimeout_negativeFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("connection_timeout", "-1");
        assertEquals(RequestContext.DEFAULT_CONNECTION_TIMEOUT, client.getConnectionTimeout(paramMap));
    }

    /** See {@link #test_connectionTimeout_negativeFallsBackToDefault} -- same failure mode. */
    @Test
    public void test_readTimeout_negativeFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("read_timeout", "-1");
        assertEquals(RequestContext.DEFAULT_READ_TIMEOUT, client.getReadTimeout(paramMap));
    }
}
