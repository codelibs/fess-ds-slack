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
package org.codelibs.fess.ds.slack.api;

import java.util.concurrent.atomic.AtomicLong;

import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.ds.slack.SlackApiMockServer;
import org.codelibs.fess.ds.slack.UnitDsTestCase;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoRequest;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class RequestRetryTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    /** A 429 with Retry-After must be retried, and the second attempt's body used. */
    @Test
    public void test_retriesOn429() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("retry_interval", "10");
        final TeamInfoResponse response = server.newClient(paramMap).teamInfo().execute();

        assertTrue(response.ok());
        assertEquals("T1", response.getTeam().getId());
        assertEquals(2, server.getRequestCount("/api/team.info"));
    }

    /** Retries are bounded: with max_retry_count=1 only two attempts are made. */
    @Test
    public void test_retriesAreBounded() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("max_retry_count", "1");
        paramMap.put("retry_interval", "10");
        server.newClient(paramMap).teamInfo().execute();

        assertEquals(2, server.getRequestCount("/api/team.info"));
    }

    /** A 500 is retried too. */
    @Test
    public void test_retriesOn500() {
        server.enqueue("/api/team.info", SlackApiMockServer.status(500, "{\"ok\":false,\"error\":\"internal_error\"}"));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("retry_interval", "10");
        assertTrue(server.newClient(paramMap).teamInfo().execute().ok());
        assertEquals(2, server.getRequestCount("/api/team.info"));
    }

    /** A 200 is never retried. */
    @Test
    public void test_doesNotRetryOn200() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        server.newClient(paramMap).teamInfo().execute();
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    /**
     * Covers the Critical finding's second layer: once every attempt is exhausted, {@code
     * execute} must not silently hand back an ordinary-looking body -- it must flag the response
     * via {@link org.codelibs.fess.ds.slack.api.Response#retriesExhausted()} so {@code
     * SlackClient} can tell "we gave up" from "Slack said no".
     */
    @Test
    public void test_exhaustedRetries_marksResponseAsRetriesExhausted() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("max_retry_count", "0");
        final TeamInfoResponse response = server.newClient(paramMap).teamInfo().execute();

        assertTrue("a response returned only because retries were exhausted must be flagged", response.retriesExhausted());
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    /** A retry that succeeds within budget must not be flagged as exhausted. */
    @Test
    public void test_successfulRetry_doesNotMarkRetriesExhausted() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(0));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("retry_interval", "10");
        final TeamInfoResponse response = server.newClient(paramMap).teamInfo().execute();

        assertFalse("a response answered within the retry budget must not be flagged", response.retriesExhausted());
    }

    /** An ordinary, never-retried 200 must not be flagged as exhausted either. */
    @Test
    public void test_nonRetryableResponse_doesNotMarkRetriesExhausted() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final TeamInfoResponse response = server.newClient(paramMap).teamInfo().execute();
        assertFalse(response.retriesExhausted());
    }

    /**
     * Covers the Retry-After overflow/negative guard: a negative value must fall back to the
     * exponential backoff, not reach {@code Thread.sleep} negative and throw.
     */
    @Test
    public void test_negativeRetryAfter_fallsBackToBackoffInsteadOfThrowing() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited("-5"));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("retry_interval", "5");
        final TeamInfoResponse response = server.newClient(paramMap).teamInfo().execute();

        assertTrue("must fall back to the backoff and eventually succeed, not throw", response.ok());
        assertEquals(2, server.getRequestCount("/api/team.info"));
    }

    /**
     * Covers the Retry-After overflow guard: a value large enough to overflow when multiplied by
     * 1000 must clamp to {@code MAX_RETRY_WAIT_MILLIS}, not wrap around into a negative wait.
     * Overrides {@code sleepBeforeRetry} to capture the computed wait without actually sleeping
     * for a full minute -- this test is about the arithmetic, not the delay itself.
     */
    @Test
    public void test_hugeRetryAfter_clampsInsteadOfOverflowing() {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(Long.toString(Long.MAX_VALUE / 500)));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\"}}"));

        final RequestContext requestContext = new RequestContext("xoxb-test");
        requestContext.setRetry(1, 10L);
        final AtomicLong capturedWaitMillis = new AtomicLong(-1L);
        final TeamInfoRequest request = new TeamInfoRequest(requestContext) {
            @Override
            protected void sleepBeforeRetry(final CurlResponse response, final int attempt, final int status) {
                capturedWaitMillis.set(Math.min(getRetryWaitMillis(response, attempt), MAX_RETRY_WAIT_MILLIS));
                // Deliberately does not call Thread.sleep: this test only needs the computed
                // wait, not to actually wait out MAX_RETRY_WAIT_MILLIS.
            }
        };

        final TeamInfoResponse response = request.execute();

        assertTrue("must succeed after the (skipped) retry", response.ok());
        assertEquals("a header value that overflows when multiplied by 1000 must clamp to the cap, not wrap negative",
                Request.MAX_RETRY_WAIT_MILLIS, capturedWaitMillis.get());
    }
}
