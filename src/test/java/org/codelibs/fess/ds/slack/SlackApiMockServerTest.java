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

import java.util.List;
import java.util.Map;

import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackApiMockServerTest extends UnitDsTestCase {

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

    @Test
    public void test_serveJson() {
        server.enqueue("/api/team.info",
                SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\",\"name\":\"NAME\",\"domain\":\"DOMAIN\"}}"));
        final SlackClient client = newClient();
        final TeamInfoResponse response = client.teamInfo().execute();
        assertTrue(response.ok());
        assertEquals("T1", response.getTeam().getId());
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    @Test
    public void test_recordsQueryParameters() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        newClient().teamInfo().execute();
        final List<Map<String, String>> requests = server.getRequests("/api/team.info");
        assertEquals(1, requests.size());
    }

    /**
     * The client cannot yet observe status codes, so verify the 429 shape with a
     * direct HTTP call. The retry layer in Phase 2 depends on this working.
     */
    @Test
    public void test_rateLimitedResponseCarriesRetryAfter() throws Exception {
        server.enqueue("/api/team.info", SlackApiMockServer.rateLimited(7));

        final java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) java.net.URI.create(server.getEndpoint() + "team.info").toURL().openConnection();
        try {
            assertEquals(429, conn.getResponseCode());
            assertEquals("7", conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    private SlackClient newClient() {
        final org.codelibs.fess.entity.DataStoreParams paramMap = new org.codelibs.fess.entity.DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return new SlackClient(paramMap);
    }
}
