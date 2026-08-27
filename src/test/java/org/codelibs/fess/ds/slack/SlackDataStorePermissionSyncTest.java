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
     * 5/8/9: the three-source merge -- member roles, {@code default_permissions} (encoded), and
     * the DataConfig permission already carried on {@code defaultDataMap} -- must all be present
     * and deduplicated, and the DataConfig value must survive byte-for-byte: proof that this
     * third source is not silently omitted (the real regression risk this guards against, F15 --
     * see {@code mergeAdditionalRoles}'s javadoc for why re-encoding it would be harmless, not
     * corrupting, and so is not what this test is about).
     */
    @Test
    public void test_threeSourceMergeIncludesAllSourcesWithoutDuplication() {
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
        assertTrue("the DataConfig permission must survive, byte-for-byte, not omitted", roles.contains(alreadyEncodedAdminPermission));
        assertEquals("all three sources present, none duplicated", 3, roles.size());
    }

    /**
     * CRITICAL (whole-branch review, Phase 3): {@code permission_sync}'s computed roles reach the
     * indexed document only through an explicit {@code role=message.roles} script mapping (see
     * {@code PERMISSION_SYNC}'s javadoc) -- a script that never references {@code message.roles},
     * such as the README's own pre-fix example (title/digest/content/created/timestamp/url), pins
     * the per-channel member roles right where {@code storeData} computed them, then never applies
     * them: only the DataConfig-level permission already carried on {@code defaultDataMap} --
     * copied into {@code dataMap} verbatim before any script runs -- survives into the indexed
     * "role" field.
     */
    @Test
    public void test_computedRolesAreDiscardedWithoutAnExplicitRoleScriptMapping() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", "alice@example.com")));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1"));
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        // The README's own example script -- title/digest/content/created/timestamp/url -- never
        // maps role=message.roles.
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("title", "message.user");
        scriptMap.put("content", "message.text");
        scriptMap.put("url", "message.permalink");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, Object> defaultDataMap = new HashMap<>();
        final String dataConfigPermission = fessConfig.getRoleSearchRolePrefix() + "admin";
        defaultDataMap.put(fessConfig.getIndexFieldRole(), new ArrayList<>(List.of(dataConfigPermission)));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, defaultDataMap);

        assertEquals(1, callback.size());
        @SuppressWarnings("unchecked")
        final List<String> role = (List<String>) callback.getDataMaps().get(0).get(fessConfig.getIndexFieldRole());
        assertEquals(List.of(dataConfigPermission), role);
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
     * Ruling (whole-branch review, Phase 3): a private channel returning {@code ok:true,
     * members:[]} is anomalous, not legitimately memberless -- the crawling token's own bot user
     * must itself be a member to read a private channel at all -- so it must fail closed exactly
     * like a members-lookup failure, not fall through to being indexed under only
     * default_permissions/the DataConfig permission.
     */
    @Test
    public void test_failClosedWhenMembersLookupReturnsEmptyList() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson());

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
            dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>());

            assertEquals(0, callback.size());
            assertEquals("a skipped channel must never be walked for messages", 0, server.getRequestCount("/api/conversations.history"));
            assertTrue("the warning must call out the empty membership as anomalous",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN).stream().anyMatch(m -> m.contains("zero members")));
        } finally {
            appender.detach();
        }
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

    /**
     * IMPORTANT-2 (whole-branch review, Phase 3, design spec Section 6.3): a skipped channel must
     * be recorded via {@code FailureUrlService}, not only warned about, so it is queryable in the
     * admin UI and survives log rotation.
     */
    @Test
    public void test_skippedChannelIsRecordedInFailureUrlService() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final List<String> recordedUrls = new ArrayList<>();
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                recordedUrls.add(url);
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());

        dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

        assertEquals("the skipped channel must be recorded via FailureUrlService", 1, recordedUrls.size());
        assertTrue("the recorded identifier must name the skipped channel", recordedUrls.get(0).contains("C1"));
    }

    /**
     * IMPORTANT-3 (whole-branch review, Phase 3) + Minor: an unresolved member -- a blank email or
     * no email at all -- must not block a channel that has at least one other resolving member,
     * but must still be counted and folded into the aggregate warning. A blank (not null) email
     * must not silently produce the bogus "just the prefix" role, nor count toward
     * {@code resolvedCount}.
     */
    @Test
    public void test_unresolvedMembersInAnIndexedChannelAreCountedInTheAggregateWarning() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", "alice@example.com"), userJson("U2", ""), userJson("U3", null)));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1", "U2", "U3"));
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
            dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

            assertEquals(1, callback.size());
            @SuppressWarnings("unchecked")
            final List<String> roles = (List<String>) callback.getDataMaps().get(0).get("captured_roles");
            final FessConfig fessConfig = ComponentUtil.getFessConfig();
            assertEquals(List.of(fessConfig.getRoleSearchUserPrefix() + "alice@example.com"), roles);

            assertTrue("the aggregate warning must count both unresolved members (U2's blank email, U3's missing email)",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                            .stream()
                            .anyMatch(m -> m.contains("2") && m.contains("did not resolve to a role")));
        } finally {
            appender.detach();
        }
    }

    /**
     * Minor (whole-branch review, Phase 3): the aggregate warning must still fire for a channel
     * skipped earlier in the walk even when a later channel's processing throws a
     * {@code SlackApiException} out of {@code getChannels} (a fatal error a later channel's
     * conversations.history call is documented to propagate directly out of {@code storeData}).
     */
    @Test
    public void test_aggregateWarningStillFiresWhenGetChannelsThrows() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true), channelJson("C2", "general", false)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        server.enqueue("/api/conversations.history", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(org.codelibs.fess.ds.slack.api.SlackApiException.class,
                    () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

            assertTrue(
                    "the earlier-skipped channel's count must still be warned about even though a later channel's "
                            + "conversations.history call threw a fatal SlackApiException out of getChannels",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN).stream().anyMatch(m -> m.contains("private channel(s)")));
        } finally {
            appender.detach();
        }
    }

    /**
     * D1: permission_sync=false plus include_private=true indexes private-channel content under
     * DataConfig permissions alone -- a de facto publish switch when that permission field is
     * left empty -- so this combination must warn, once per crawl, without being forbidden
     * (existing operators run with it today; forbidding it would be a breaking change).
     */
    @Test
    public void test_warnsOncePermissionSyncDisabledAndIncludePrivateEnabled() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true), channelJson("C2", "hush", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history", historyJson());
        server.enqueue("/api/conversations.history", historyJson());

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("include_private", "true");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

            final List<String> permissionSyncWarnings = appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                    .stream()
                    .filter(m -> m.contains("permission_sync") && m.contains("include_private"))
                    .collect(java.util.stream.Collectors.toList());
            assertEquals("exactly one aggregate warning per crawl, not one per channel", 1, permissionSyncWarnings.size());
        } finally {
            appender.detach();
        }
    }

    /** The combination is not forbidden: storeData must still index normally, not throw. */
    @Test
    public void test_permissionSyncDisabledAndIncludePrivateEnabledStillIndexes() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history",
                historyJson(messageJson("Hello", "1111111111.000100", "https://example.slack.com/archives/C1/p1111111111000100")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("include_private", "true");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, new HashMap<>(), new HashMap<>());

        assertEquals(1, callback.size());
    }

    /** permission_sync=true plus include_private=true is the recommended combination: no warning. */
    @Test
    public void test_noWarningWhenPermissionSyncEnabled() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "general", false)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history", historyJson());

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("include_private", "true");
        paramMap.put("permission_sync", "true");

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

            assertFalse("permission_sync=true must not trigger the D1 warning",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                            .stream()
                            .anyMatch(m -> m.contains("permission_sync") && m.contains("include_private")));
        } finally {
            appender.detach();
        }
    }

    /** include_private unset (or false) must not trigger the D1 warning either. */
    @Test
    public void test_noWarningWhenIncludePrivateDisabled() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "general", false)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history", historyJson());

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), baseParamMap(), new HashMap<>(), new HashMap<>());

            assertFalse("include_private disabled must not trigger the D1 warning",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                            .stream()
                            .anyMatch(m -> m.contains("permission_sync") && m.contains("include_private")));
        } finally {
            appender.detach();
        }
    }

    /**
     * Minor (whole-branch review, Phase 3): the D1 warning must not fire when the operator has
     * already set a DataConfig-level permission -- that crawl is not actually unrestricted, so
     * warning regardless would be a false alarm on every crawl for an operator who did the right
     * thing.
     */
    @Test
    public void test_noWarningWhenDataConfigPermissionIsSet() {
        server.enqueue("/api/users.list", usersListJson());
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.history", historyJson());

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("include_private", "true");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, Object> defaultDataMap = new HashMap<>();
        defaultDataMap.put(fessConfig.getIndexFieldRole(), new ArrayList<>(List.of(fessConfig.getRoleSearchRolePrefix() + "admin")));

        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), defaultDataMap);

            assertFalse("a populated DataConfig permission must not trigger the D1 warning",
                    appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                            .stream()
                            .anyMatch(m -> m.contains("permission_sync") && m.contains("include_private")));
        } finally {
            appender.detach();
        }
    }

    /**
     * Important-1 (whole-branch review, Phase 3): a file shared into both a private and a public
     * channel must end up with the union of both channels' roles, not whichever channel's
     * files.list happened to be walked last. Channel order here -- private (C1) first, public
     * (C2) second, matching {@code conversations.list}'s response order -- is exactly the
     * scenario the review called out: with the pre-fix code, the last {@code callback.store} for
     * this shared URL would carry only C2's empty (unrestricted) roles, silently dropping
     * alice's restriction.
     */
    @Test
    public void test_fileSharedAcrossChannelsGetsUnionOfRoles() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", "alice@example.com")));
        server.enqueue("/api/conversations.list", channelsListJson(channelJson("C1", "secret", true), channelJson("C2", "general", false)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1"));
        server.enqueue("/api/conversations.history", historyJson());
        server.enqueue("/api/conversations.history", historyJson());
        server.enqueue("/api/files.list", filesListJson(fileJson("F1", "https://example.slack.com/files/F1")));
        server.enqueue("/api/files.list", filesListJson(fileJson("F1", "https://example.slack.com/files/F1")));

        final DataStoreParams paramMap = baseParamMap();
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");
        paramMap.put("file_crawl", "true");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("captured_roles", "message.roles");
        scriptMap.put("url", "message.permalink");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        final List<Map<String, Object>> forFile = callback.getDataMaps()
                .stream()
                .filter(m -> "https://example.slack.com/files/F1".equals(m.get("url")))
                .collect(java.util.stream.Collectors.toList());
        assertEquals("both channels' files.list calls must have stored this shared file", 2, forFile.size());
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        @SuppressWarnings("unchecked")
        final List<String> lastStoredRoles = (List<String>) forFile.get(forFile.size() - 1).get("captured_roles");
        assertTrue(
                "the union must retain alice's role from the private channel even though the public channel (with no roles "
                        + "of its own) was processed last",
                lastStoredRoles.contains(fessConfig.getRoleSearchUserPrefix() + "alice@example.com"));
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

    private SlackApiMockServer.MockResponse filesListJson(final String... fileJsons) {
        return SlackApiMockServer.json("{\"ok\":true,\"files\":[" + String.join(",", fileJsons) + "]," + "\"paging\":{\"count\":"
                + fileJsons.length + ",\"total\":" + fileJsons.length + ",\"page\":1,\"pages\":1}}");
    }

    // url_private_download points at this mock server's own "/api/" context so the download
    // "succeeds" against the default (or strict-mode-unscripted) response, matching the pattern
    // SlackDataStoreMessageFileRethrowSlackApiExceptionTest already established.
    private String fileJson(final String id, final String permalink) {
        return "{\"id\":\"" + id + "\",\"permalink\":\"" + permalink + "\",\"mimetype\":\"text/plain\",\"size\":10,"
                + "\"name\":\"f.txt\",\"url_private_download\":\"" + server.getEndpoint() + "download/" + id + "\"}";
    }
}
