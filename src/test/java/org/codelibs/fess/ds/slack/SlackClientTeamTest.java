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

import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackClientTeamTest extends UnitDsTestCase {

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

    /** A successful team.info call returns the team. */
    @Test
    public void test_getTeamReturnsTeamOnSuccess() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);

        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\",\"domain\":\"example\"}}"));

        final Team team = client.getTeam();

        assertNotNull(team);
        assertEquals("T1", team.getId());
    }

    /**
     * A missing team:read scope must not be swallowed silently: getTeam()
     * still returns null (callers fall back to per-message permalink
     * resolution), but the failure is now reported via a warning instead of
     * disappearing.
     */
    @Test
    public void test_getTeamReturnsNullOnFailure() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        final SlackClient client = server.newClient(paramMap);

        server.enqueue("/api/team.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"missing_scope\"}"));

        final Team team = client.getTeam();

        assertNull(team);
    }
}
