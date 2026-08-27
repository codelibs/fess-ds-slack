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

    private SlackClient newClient() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return server.newClient(paramMap);
    }
}
