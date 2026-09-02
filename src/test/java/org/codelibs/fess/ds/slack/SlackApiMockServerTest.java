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

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.junit.jupiter.api.Assertions;
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
        try {
            if (server != null) {
                server.stop();
            }
        } finally {
            super.tearDown(testInfo);
        }
    }

    @Test
    public void test_serveJson() {
        server.enqueue("/api/team.info",
                SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\",\"name\":\"NAME\",\"domain\":\"DOMAIN\"}}"));
        final SlackClient client = server.newClient("xoxb-test");
        final TeamInfoResponse response = client.teamInfo().execute();
        assertTrue(response.ok());
        assertEquals("T1", response.getTeam().getId());
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    /**
     * The {@link SlackClient} constructor unconditionally preloads users via
     * {@code usersList().limit(100).execute()} (see {@code SlackClient.DEFAULT_USER_COUNT}),
     * sending a plain {@code limit=100} query parameter. Verify the parameter both arrives
     * and is parsed back with its key and value intact.
     */
    @Test
    public void test_recordsQueryParameters() {
        server.newClient("xoxb-test");
        final List<Map<String, String>> requests = server.getRequests("/api/users.list");
        assertEquals(1, requests.size());
        assertEquals("100", requests.get(0).get("limit"));
    }

    /**
     * Pins the OAuth token onto the wire. {@code Request#getCurlRequest} sends it as an
     * {@code Authorization} header on every API call, which no assertion in the suite could
     * see while the harness recorded only query strings &mdash; deleting the header line
     * outright still left every test green. The constructor's own {@code users.list} preload
     * is the most ordinary API call there is, so it serves as the sample.
     */
    @Test
    public void test_recordsAuthorizationHeader() {
        server.newClient("xoxb-test");
        final List<String> authorizations = server.getAuthorizations("/api/users.list");
        assertEquals(1, authorizations.size());
        assertEquals("Bearer xoxb-test", authorizations.get(0));
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
        final SlackClient client = server.newClient("xoxb-test");
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

        final HttpURLConnection conn = (HttpURLConnection) URI.create(server.getEndpoint() + "team.info").toURL().openConnection();
        try {
            assertEquals(429, conn.getResponseCode());
            assertEquals("7", conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
        assertEquals(1, server.getRequestCount("/api/team.info"));
    }

    @Test
    public void test_getQueuedCount_decrementsAsResponsesAreConsumed() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        assertEquals(2, server.getQueuedCount("/api/team.info"));

        final SlackClient client = server.newClient("xoxb-test");
        client.teamInfo().execute();
        assertEquals(1, server.getQueuedCount("/api/team.info"));
    }

    @Test
    public void test_getQueuedCount_zeroForPathNeverEnqueued() {
        assertEquals(0, server.getQueuedCount("/api/never.enqueued"));
    }

    @Test
    public void test_assertAllConsumed_passesWhenNothingIsLeftOver() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        final SlackClient client = server.newClient("xoxb-test");
        client.teamInfo().execute();

        server.assertAllConsumed();
    }

    @Test
    public void test_assertAllConsumed_throwsAndNamesTheLeftoverPath() {
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true}"));
        server.newClient("xoxb-test");
        // team.info is never called, so both enqueued responses are left over.

        final AssertionError error = Assertions.assertThrows(AssertionError.class, server::assertAllConsumed);
        assertTrue(error.getMessage().contains("/api/team.info"));
        assertTrue(error.getMessage().contains("2"));
    }

    @Test
    public void test_strict_defaultsToOffAndServesDefaultResponse() {
        final SlackClient client = server.newClient("xoxb-test");
        // team.info was never enqueued for; non-strict mode serves DEFAULT_RESPONSE_BODY,
        // which is "ok":true.
        assertTrue(client.teamInfo().execute().ok());
    }

    @Test
    public void test_strict_answersUnscriptedRequestWithError() {
        server.setStrict(true);
        final SlackClient client = server.newClient("xoxb-test");
        // team.info was never enqueued for; strict mode must fail loudly instead of
        // quietly returning DEFAULT_RESPONSE_BODY's "ok":true.
        final TeamInfoResponse response = client.teamInfo().execute();
        assertFalse(response.ok());
        assertEquals("unscripted_request", response.getError());
    }

    @Test
    public void test_strict_unscriptedRequestReturns503() throws Exception {
        server.setStrict(true);
        final HttpURLConnection conn = (HttpURLConnection) URI.create(server.getEndpoint() + "team.info").toURL().openConnection();
        try {
            assertEquals(503, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Reproduces, correctly this time, the scenario the review demonstrated as broken:
     * enqueueing a {@code conversations.list} page and then reading it back via
     * {@link SlackClient#getAllChannels}. The fix is ordering, not new plumbing: construct the
     * client through {@link SlackApiMockServer#newClient} first (so the constructor's own
     * preload consumes only the default body), and only then enqueue the page this test cares
     * about.
     */
    @Test
    public void test_newClient_thenEnqueueForPreloadedPath_isNotEatenByConstructor() {
        final SlackClient client = server.newClient("xoxb-test");
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"NAME\"}]," + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Channel> channels = new ArrayList<>();
        client.getAllChannels(channels::add);

        assertEquals(1, channels.size());
        assertEquals("C1", channels.get(0).getId());
    }

    @Test
    public void test_getEndpoint_throwsBeforeStart() {
        final SlackApiMockServer notStarted = new SlackApiMockServer();
        Assertions.assertThrows(IllegalStateException.class, notStarted::getEndpoint);
    }

    @Test
    public void test_getEndpoint_throwsAfterStop() {
        server.stop();
        Assertions.assertThrows(IllegalStateException.class, server::getEndpoint);
        // re-start so tearDown's server.stop() is harmless.
        try {
            server.start();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
