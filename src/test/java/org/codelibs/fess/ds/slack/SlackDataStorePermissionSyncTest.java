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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.PermissionHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.script.ScriptEngineFactory;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Exercises ACL synchronisation end to end through {@code storeData}: {@code permission_sync},
 * {@code default_permissions}, the member-roles/{@code default_permissions}/DataConfig-permission
 * three-source merge, and the fail-closed rule for a private channel whose membership could not
 * be determined. See the design plan's D2/D3/D5 and F5-F9/F12-F15.
 *
 * <p>
 * {@link SlackApiMockServer#setStrict} is on throughout this class: the default response body
 * carries {@code "members":[]} (F12), so a forgotten stub for {@code conversations.members} would
 * otherwise read as "a channel with no members" instead of failing loudly -- exactly backwards
 * for a fail-closed feature.
 * </p>
 */
public class SlackDataStorePermissionSyncTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        server.setStrict(true);
        dataStore = new SlackDataStore();

        // Named differently from PermissionHelper's own `systemHelper` field on purpose: naming
        // this local the same, as the plan's own snippet does, makes `this.systemHelper =
        // systemHelper;` below a self-assignment no-op -- Java resolves the unqualified RHS
        // `systemHelper` to the anonymous subclass's own *inherited* field, not this local
        // variable, since a nested class's members shadow an enclosing scope's same-named
        // variable. Verified empirically (a trivial two-line repro prints `null`, not the
        // expected value, when both are named identically). PermissionHelper is not registered
        // in this module's test container by convention, and even if it were, @Resource
        // injection does not run for a component handed to ComponentUtil.register directly, so
        // it is wired in by hand here (F14).
        final SystemHelper helper = new SystemHelper();
        ComponentUtil.register(helper, "systemHelper");
        ComponentUtil.register(new PermissionHelper() {
            {
                this.systemHelper = helper;
            }
        }, "permissionHelper");

        final CrawlerStatsHelper crawlerStatsHelper = new CrawlerStatsHelper();
        crawlerStatsHelper.init();
        ComponentUtil.register(crawlerStatsHelper, "crawlerStatsHelper");

        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());

        final ScriptEngineFactory scriptEngineFactory = new ScriptEngineFactory();
        scriptEngineFactory.add("groovy", (template, resultMap) -> {
            Object value = resultMap;
            for (final String part : template.split("\\.")) {
                if (!(value instanceof Map)) {
                    return null;
                }
                value = ((Map<?, ?>) value).get(part);
            }
            return value;
        });
        ComponentUtil.register(scriptEngineFactory, "scriptEngineFactory");
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    /** 1: permission_sync unset must never call conversations.members. */
    @Test
    public void test_permissionSyncUnsetNeverCallsMembers() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, baseParamMap(), new HashMap<>(), new HashMap<>());

        assertEquals(0, server.getRequestCount("/api/conversations.members"));
        assertEquals(1, callback.size());
    }

    /** 2: permission_sync unset must not expose message.roles to scripts (byte-identical to before). */
    @Test
    public void test_permissionSyncUnsetHidesMessageRoles() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, baseParamMap(), scriptMap, new HashMap<>());

        assertEquals(1, callback.size());
        assertNull("message.roles must not exist when permission_sync is unset", callback.getDataMaps().get(0).get("captured_roles"));
    }

    /** 3: permission_sync=true on a public channel must not call conversations.members. */
    @Test
    public void test_publicChannelNeverCallsMembersEvenWhenSyncEnabled() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "general", false)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        assertEquals(0, server.getRequestCount("/api/conversations.members"));
        assertEquals(1, callback.size());
        assertEquals(java.util.Collections.emptyList(), callback.getDataMaps().get(0).get("captured_roles"));
    }

    /** 4: permission_sync=true on a private channel resolves member emails into roles. */
    @Test
    public void test_privateChannelResolvesMemberEmailsToRoles() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", "alice@example.com"), userJson("U2", "bob@example.com")));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1", "U2"));
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        assertEquals(1, callback.size());
        @SuppressWarnings("unchecked")
        final List<String> roles = (List<String>) callback.getDataMaps().get(0).get("captured_roles");
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        assertTrue("alice's role must be present", roles.contains(fessConfig.getRoleSearchUserPrefix() + "alice@example.com"));
        assertTrue("bob's role must be present", roles.contains(fessConfig.getRoleSearchUserPrefix() + "bob@example.com"));
        assertEquals(2, roles.size());
    }

    /**
     * 5/8/9: the three-source merge -- member roles (not encoded), default_permissions (encoded),
     * and the DataConfig permission already carried on defaultDataMap (not re-encoded, F8/F9) --
     * must all be present, deduplicated, and the DataConfig value must survive byte-for-byte.
     */
    @Test
    public void test_threeSourceMergeDoesNotDoubleEncodeDataConfigPermission() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", "alice@example.com")));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1"));
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");
        paramMap.put("default_permissions", "{group}sales");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        // Simulates what AbstractDataStore#store already put here before storeData ever runs
        // (F8): an admin-UI permission, already encoded on save -- e.g. {role}admin -> 3admin --
        // by the time it reaches defaultDataMap.
        final Map<String, Object> defaultDataMap = new HashMap<>();
        final String alreadyEncodedAdminPermission = fessConfig.getRoleSearchRolePrefix() + "admin";
        defaultDataMap.put(fessConfig.getIndexFieldRole(), new ArrayList<>(List.of(alreadyEncodedAdminPermission)));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, defaultDataMap);

        assertEquals(1, callback.size());
        @SuppressWarnings("unchecked")
        final List<String> roles = (List<String>) callback.getDataMaps().get(0).get("captured_roles");
        assertTrue("member role must be present", roles.contains(fessConfig.getRoleSearchUserPrefix() + "alice@example.com"));
        assertTrue("default_permissions must be encoded", roles.contains(fessConfig.getRoleSearchGroupPrefix() + "sales"));
        assertTrue("the DataConfig permission must survive, byte-for-byte, not re-encoded", roles.contains(alreadyEncodedAdminPermission));
        assertEquals("the {role}-prefixed literal must never appear: encoding it a second time would look like this", false,
                roles.contains("{role}" + alreadyEncodedAdminPermission));
        assertEquals(3, roles.size());
    }

    /** 6: fail-closed -- a private channel whose membership could not be fetched is not indexed. */
    @Test
    public void test_failClosedWhenMembersLookupFails() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>());

        assertEquals(0, callback.size());
        assertEquals("a skipped channel must never be walked for messages", 0, server.getRequestCount("/api/conversations.history"));
    }

    /**
     * 7: fail-closed -- a private channel with members but zero resolved roles (every member's
     * email came back null, as happens when the token lacks users:read.email) is not indexed,
     * and the warning names the missing scope.
     */
    @Test
    public void test_failClosedWhenNoMemberResolvesToARole() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", null), userJson("U2", null)));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1", "U2"));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
            dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>());

            assertEquals(0, callback.size());
            assertEquals("a skipped channel must never be walked for messages", 0, server.getRequestCount("/api/conversations.history"));
            assertTrue("the warning must name the missing scope",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN).stream().anyMatch(m -> m.contains("users:read.email")));
        } finally {
            appender.detach();
        }
    }

    private DataStoreParams baseParamMap() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return paramMap;
    }

    private SlackApiMockServer.MockResponse usersListJson(final String... userJsons) {
        return SlackApiMockServer
                .json("{\"ok\":true,\"members\":[" + String.join(",", userJsons) + "],\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private String userJson(final String id, final String email) {
        // "name" is required on every user: SlackClient's constructor preload caches each user
        // under both its ID and its name (usersCache.put(user.getName(), user)), and Guava's
        // LoadingCache rejects a null key.
        if (email == null) {
            return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}";
        }
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"profile\":{\"email\":\"" + email + "\"}}";
    }

    private SlackApiMockServer.MockResponse channelsListJson(final String... channelJsons) {
        return SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[" + String.join(",", channelJsons) + "],\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private String channelJson(final String id, final String name, final boolean isPrivate) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"is_private\":" + isPrivate + "}";
    }

    private SlackApiMockServer.MockResponse teamInfoJson() {
        return SlackApiMockServer.json("{\"ok\":true,\"team\":{\"id\":\"T1\",\"domain\":\"example\"}}");
    }

    private SlackApiMockServer.MockResponse historyJson(final String... messageJsons) {
        return SlackApiMockServer.json("{\"ok\":true,\"messages\":[" + String.join(",", messageJsons) + "],\"has_more\":false,"
                + "\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private String messageJson(final String text, final String ts, final String permalink) {
        return "{\"text\":\"" + text + "\",\"ts\":\"" + ts + "\",\"permalink\":\"" + permalink + "\"}";
    }

    private SlackApiMockServer.MockResponse membersJson(final String... ids) {
        final StringBuilder members = new StringBuilder();
        for (final String id : ids) {
            if (members.length() > 0) {
                members.append(',');
            }
            members.append('"').append(id).append('"');
        }
        return SlackApiMockServer.json("{\"ok\":true,\"members\":[" + members + "],\"response_metadata\":{\"next_cursor\":\"\"}}");
    }
}
