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

import org.codelibs.fess.ds.slack.SlackApiMockServer;
import org.codelibs.fess.ds.slack.UnitDsTestCase;
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
}
