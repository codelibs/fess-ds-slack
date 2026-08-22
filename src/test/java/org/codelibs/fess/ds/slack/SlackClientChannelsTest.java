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

import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackClientChannelsTest extends UnitDsTestCase {

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

    /**
     * `channels=general, random` must resolve both names; the space after the
     * comma used to make the second lookup fail.
     */
    @Test
    public void test_channelNamesAreTrimmed() {
        server.enqueue("/api/conversations.list", SlackApiMockServer.json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"},"
                + "{\"id\":\"C2\",\"name\":\"random\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general, random");

        final List<Channel> channels = new ArrayList<>();
        server.newClient(paramMap).getChannels(channels::add);

        assertEquals(2, channels.size());
        assertEquals("general", channels.get(0).getName());
        assertEquals("random", channels.get(1).getName());
    }

    /**
     * A blank `channels` value must not be treated as a channel named "".
     */
    @Test
    public void test_blankChannelsIsIgnored() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "");

        final List<Channel> channels = new ArrayList<>();
        server.newClient(paramMap).getChannels(channels::add);

        assertEquals(0, channels.size());
    }
}
