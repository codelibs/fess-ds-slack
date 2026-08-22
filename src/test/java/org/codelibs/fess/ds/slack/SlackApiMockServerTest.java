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

    /**
     * The {@link SlackClient} constructor unconditionally preloads users via
     * {@code usersList().limit(100).execute()} (see {@code newClient()} and
     * {@code SlackClient.DEFAULT_USER_COUNT}), sending a plain
     * {@code limit=100} query parameter. Verify the parameter both arrives
     * and is parsed back with its key and value intact.
     */
    @Test
    public void test_recordsQueryParameters() {
        newClient();
        final List<Map<String, String>> requests = server.getRequests("/api/users.list");
        assertEquals(1, requests.size());
        assertEquals("100", requests.get(0).get("limit"));
    }

    /**
     * {@code cursor} is a free-form string parameter (see
     * {@code UsersListRequest.cursor(String)}), so drive a value containing a
     * space and an ampersand through the real client and confirm
     * {@link SlackApiMockServer}'s query parser decodes exactly what
     * {@code org.codelibs.curl.CurlRequest#param} encoded. Phase 1's first
     * task depends on this decoding being correct: it asserts the parsed
     * {@code types} parameter across successive {@code files.list} pages, and
     * a broken decoder would fail that assertion for a reason no one could
     * locate.
     */
    @Test
    public void test_decodesUrlEncodedQueryParameterValue() {
        final SlackClient client = newClient();
        client.usersList().cursor("a value with a space & an ampersand").execute();

        final List<Map<String, String>> requests = server.getRequests("/api/users.list");
        // index 0 is the constructor's own users.list preload (asserted in
        // test_recordsQueryParameters); index 1 is this test's explicit call.
        assertEquals(2, requests.size());
        assertEquals("a value with a space & an ampersand", requests.get(1).get("cursor"));
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
