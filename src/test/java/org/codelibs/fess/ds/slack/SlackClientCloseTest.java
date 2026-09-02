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

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers {@link SlackClient#close()}: it must release every piece of in-memory state this
 * client accumulates, not only the three caches. {@code preloadedChannels} (added after
 * {@code close()} was last touched) is a bounded, evictable structure only as long as
 * something actually empties it; before this test, {@code close()} left it untouched, so a
 * closed client still pinned its full preloaded channel listing in memory.
 */
public class SlackClientCloseTest extends UnitDsTestCase {

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
    public void test_closeClearsPreloadedChannels() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);

        assertFalse("the constructor preload must have captured the channel before close()", client.preloadedChannels.isEmpty());

        client.close();

        assertTrue("close() must clear preloadedChannels, not only the three caches", client.preloadedChannels.isEmpty());
    }

    @Test
    public void test_closeInvalidatesCaches() throws Exception {
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":true,\"user\":{\"id\":\"U1\",\"name\":\"user1\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);
        client.getUser("U1");
        assertEquals("the user must be cached before close()", 1L, client.usersCache.size());

        client.close();

        assertEquals("close() must invalidate usersCache", 0L, client.usersCache.size());
        assertEquals("close() must invalidate botsCache", 0L, client.botsCache.size());
        assertEquals("close() must invalidate channelsCache", 0L, client.channelsCache.size());
    }
}
