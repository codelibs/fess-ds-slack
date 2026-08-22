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

public class SlackDataStoreUsernameTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        dataStore = new SlackDataStore();
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    @Test
    public void test_prefersDisplayName() {
        assertEquals("DISPLAY", usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\","
                + "\"profile\":{\"display_name\":\"DISPLAY\",\"real_name\":\"Profile Real\"}}"));
    }

    /** An empty display_name must fall through, not win. */
    @Test
    public void test_emptyDisplayNameFallsThroughToProfileRealName() {
        assertEquals("Profile Real", usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\","
                + "\"profile\":{\"display_name\":\"\",\"real_name\":\"Profile Real\"}}"));
    }

    /** A null display_name must fall through too. */
    @Test
    public void test_nullDisplayNameFallsThroughToProfileRealName() {
        assertEquals("Profile Real", usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\","
                + "\"profile\":{\"display_name\":null,\"real_name\":\"Profile Real\"}}"));
    }

    /** An absent display_name key must fall through as well. */
    @Test
    public void test_absentDisplayNameFallsThroughToProfileRealName() {
        assertEquals("Profile Real", usernameFor(
                "{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\"," + "\"profile\":{\"real_name\":\"Profile Real\"}}"));
    }

    @Test
    public void test_fallsBackToTopLevelRealName() {
        assertEquals("Top Real",
                usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\",\"profile\":{\"display_name\":\"\"}}"));
    }

    @Test
    public void test_fallsBackToName() {
        assertEquals("login", usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"\",\"profile\":{\"display_name\":\"\"}}"));
    }

    /** With nothing usable, the user ID is the last resort. */
    @Test
    public void test_fallsBackToUserId() {
        assertEquals("U1", usernameFor("{\"id\":\"U1\",\"name\":\"\",\"real_name\":\"\",\"profile\":{\"display_name\":\"\"}}"));
    }

    /** A missing profile must not throw. */
    @Test
    public void test_missingProfileFallsBackToTopLevelRealName() {
        assertEquals("Top Real", usernameFor("{\"id\":\"U1\",\"name\":\"login\",\"real_name\":\"Top Real\"}"));
    }

    /**
     * An unresolvable user ID must not abort the crawl: Guava's LoadingCache
     * raises the unchecked {@code InvalidCacheLoadException} when the loader
     * returns null, which a bare {@code catch (ExecutionException)} does not
     * catch.
     */
    @Test
    public void test_unknownUserIdFallsBackToUserIdInsteadOfThrowing() {
        server.enqueue("/api/users.list",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"user_not_found\"}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);

        assertEquals("U404", dataStore.getUsername(client, "U404"));
    }

    private String usernameFor(final String userJson) {
        server.enqueue("/api/users.list",
                SlackApiMockServer.json("{\"ok\":true,\"members\":[" + userJson + "],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);
        return dataStore.getUsername(client, "U1");
    }
}
