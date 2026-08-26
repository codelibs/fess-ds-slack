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

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers {@code exclude_archived}: {@code conversations.list} accepts this parameter, defaulting
 * to {@code false} on Slack's side, but this plugin never set it, so archived channels were
 * always crawled with no way to turn that off.
 */
public class SlackClientExcludeArchivedTest extends UnitDsTestCase {

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

    /** Unset must keep current behaviour: archived channels are not excluded. */
    @Test
    public void test_excludeArchivedDefaultsToFalse() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");

        server.newClient(paramMap);

        final List<Map<String, String>> requests = server.getRequests("/api/conversations.list");
        assertEquals(1, requests.size());
        assertEquals("false", requests.get(0).get("exclude_archived"));
    }

    /** {@code exclude_archived=true} must reach {@code conversations.list}. */
    @Test
    public void test_excludeArchivedTrueIsPassedThrough() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("exclude_archived", "true");

        server.newClient(paramMap);

        final List<Map<String, String>> requests = server.getRequests("/api/conversations.list");
        assertEquals(1, requests.size());
        assertEquals("true", requests.get(0).get("exclude_archived"));
    }
}
