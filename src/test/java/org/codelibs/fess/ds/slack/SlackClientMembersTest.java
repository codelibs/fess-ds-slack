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

import java.util.ArrayList;
import java.util.List;

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Exercises {@link SlackClient#getChannelMembers}, the {@code conversations.members} paging
 * loop introduced for ACL synchronisation (see design plan F1/F11).
 */
public class SlackClientMembersTest extends UnitDsTestCase {

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
    public void test_pagesThroughMembers() {
        server.enqueue("/api/conversations.members",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[\"U1\",\"U2\"],\"response_metadata\":{\"next_cursor\":\"CUR\"}}"));
        server.enqueue("/api/conversations.members",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[\"U3\"],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<String> members = new ArrayList<>();
        newClient().getChannelMembers("C1", members::add);

        assertEquals(3, members.size());
        assertEquals("U1", members.get(0));
        assertEquals("U3", members.get(2));
        assertEquals(2, server.getRequestCount("/api/conversations.members"));
        assertEquals("CUR", server.getRequests("/api/conversations.members").get(1).get("cursor"));
    }

    @Test
    public void test_sendsChannelParameter() {
        server.enqueue("/api/conversations.members",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        newClient().getChannelMembers("C42", m -> {});
        assertEquals("C42", server.getRequests("/api/conversations.members").get(0).get("channel"));
    }

    /**
     * A channel-scoped failure (e.g. {@code channel_not_found}, which {@link
     * SlackClient#handleApiError} warns and returns for rather than throwing) must be
     * distinguishable from an empty channel: fail-closed ACL synchronisation depends on this
     * (see design plan D3/F12).
     */
    @Test
    public void test_reportsFailureDistinctlyFromEmptyMembership() {
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));

        final List<String> members = new ArrayList<>();
        final boolean succeeded = newClient().getChannelMembers("C1", members::add);

        assertFalse("a channel-scoped error must be reported as failure", succeeded);
        assertEquals(0, members.size());
    }

    @Test
    public void test_emptyMembershipIsNotAFailure() {
        server.enqueue("/api/conversations.members",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<String> members = new ArrayList<>();
        final boolean succeeded = newClient().getChannelMembers("C1", members::add);

        assertTrue("an empty member list on ok:true is a successful, zero-member result", succeeded);
        assertEquals(0, members.size());
    }

    /**
     * Minor (whole-branch review, Phase 3): a malformed {@code ok:true} response missing {@code
     * members} must not NPE -- the blast radius here differs from this class's other paging
     * methods, since an uncaught NPE would fail the whole crawl instead of just this one channel.
     */
    @Test
    public void test_missingMembersFieldIsTreatedAsFailureNotNpe() {
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":true}"));

        final List<String> members = new ArrayList<>();
        final boolean succeeded = newClient().getChannelMembers("C1", members::add);

        assertFalse("a missing \"members\" field must be treated as a channel-scoped failure", succeeded);
        assertEquals(0, members.size());
    }

    /**
     * Minor (whole-branch review, Phase 3): a malformed {@code ok:true} response missing {@code
     * response_metadata} must not NPE.
     */
    @Test
    public void test_missingResponseMetadataIsTreatedAsFailureNotNpe() {
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":true,\"members\":[\"U1\"]}"));

        final List<String> members = new ArrayList<>();
        final boolean succeeded = newClient().getChannelMembers("C1", members::add);

        assertFalse("a missing \"response_metadata\" field must be treated as a channel-scoped failure", succeeded);
        assertEquals(1, members.size());
    }

    private SlackClient newClient() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return server.newClient(paramMap);
    }
}
