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

    /**
     * Regression guard for the happy path, not a test that fails against the pre-fix
     * {@code getTeam()}: the {@code ok:true} case returned the same {@link Team} before and
     * after the {@code ok()} check was added. What this test does catch is someone inverting
     * that check later (e.g. writing {@code if (response.ok())} instead of
     * {@code if (!response.ok())}), which would turn every successful call into a silent
     * {@code null} team.
     */
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
     * Regression guard for the {@code ok:false} path, not a test that fails against the
     * pre-fix {@code getTeam()}: {@code TeamInfoResponse.getTeam()} was already {@code null}
     * whenever the response carried no {@code team} field, so this passed unchanged before the
     * {@code ok()} check existed too. What this test pins down is a decision a later phase will
     * have to revisit deliberately: the design spec classifies {@code missing_scope} as fatal,
     * while {@code getTeam()} currently warns and returns {@code null} so the caller falls back
     * to per-message permalink resolution via {@code chat.getPermalink}. Deleting this test
     * would remove the tripwire that forces that choice to be made on purpose instead of by
     * accident.
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
