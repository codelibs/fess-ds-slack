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
 * {@code usersCache} (and, identically, {@code channelsCache}) is keyed under both a user's ID
 * and its name, so {@link SlackClient} doubles the configured cache size when building it;
 * otherwise the effective capacity for distinct users is half of what was configured. This is
 * observable only through eviction behavior -- Guava's {@code LoadingCache} exposes no
 * configured-capacity accessor -- so this test drives the cache with a configured size small
 * enough (&lt; 20) that Guava allocates a single internal segment, making eviction order
 * deterministic under sequential access.
 */
public class SlackClientCacheSizingTest extends UnitDsTestCase {

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
     * With {@code user_cache_size=1}, the effective capacity must be 2 (doubled), not 1. Proof:
     * after loading two distinct users U1 and U2, re-fetching U1 must still be a cache hit -- if
     * the size were not doubled (capacity 1), loading U2 would already have evicted U1, and this
     * re-fetch would be a miss. A third distinct user, U3, then must evict the least-recently-used
     * entry (U2, since U1 was just re-touched), so re-fetching U2 afterward is a miss again.
     */
    @Test
    public void test_userCacheCapacityIsDoubledConfiguredSize() throws Exception {
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":true,\"user\":{\"id\":\"U1\",\"name\":\"user1\"}}"));
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":true,\"user\":{\"id\":\"U2\",\"name\":\"user2\"}}"));
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":true,\"user\":{\"id\":\"U3\",\"name\":\"user3\"}}"));
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":true,\"user\":{\"id\":\"U2\",\"name\":\"user2\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("user_cache_size", "1");
        final SlackClient client = server.newClient(paramMap);

        client.getUser("U1");
        client.getUser("U2");
        assertEquals("both U1 and U2 must fit at once (capacity 2, not 1)", 2, server.getRequestCount("/api/users.info"));

        client.getUser("U1");
        assertEquals("re-fetching U1 must be a cache hit while capacity is doubled to 2", 2, server.getRequestCount("/api/users.info"));

        client.getUser("U3");
        assertEquals("a third distinct user must still require a network call", 3, server.getRequestCount("/api/users.info"));

        client.getUser("U2");
        assertEquals("U2 must have been evicted by U3 once capacity 2 was exceeded", 4, server.getRequestCount("/api/users.info"));
    }
}
