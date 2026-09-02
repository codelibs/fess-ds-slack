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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.slack.api.type.Channel;
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
 * Pins the ordering half of {@code permission_sync}'s file-role handling (#41): the union
 * {@code SlackDataStore.FileRoles} accumulates is only worth as much as the order of the
 * {@code callback.store} calls that consume it.
 *
 * <p>
 * A file's permalink is channel-independent, so the same file shared into two channels is walked
 * once per channel and stored once per channel under the same URL -- and the store that lands last
 * is the document that survives. Accumulating the union atomically does not order those stores, so
 * a task that merged early could still store late and index the file with the roles of only the
 * channels merged by then. When the late storer is a public channel's task, contributing no roles
 * of its own, "only the channels merged by then" is an empty role list: a file that belongs to a
 * private channel, indexed with no access-control list at all.
 * </p>
 *
 * <p>
 * <b>The interleaving is forced, not waited for.</b> Two hooks make it deterministic rather than
 * timing-dependent:
 * </p>
 * <ul>
 * <li>{@code computeChannelRoles} is overridden to hold the <em>private</em> channel's walk on the
 * crawl's own thread until the <em>public</em> channel's file task has merged, so the public
 * channel is unambiguously the one that merges first (and therefore the one whose union is the
 * smaller of the two).</li>
 * <li>The stand-in script engine holds that same public-channel task between its merge and its
 * store -- the exact window the defect lives in -- until the private channel's task has stored.
 * That wait can only be satisfied while merge and store are separable, so it times out after
 * {@link #HOLD_SECONDS} once they are not, which is what this test costs when it passes.</li>
 * </ul>
 *
 * <p>
 * Against the unordered code the private channel's task stores first and the public channel's
 * stores last, leaving alice's role off the surviving document; against the ordered code the
 * private channel's task cannot merge until the public one has stored, so it stores last and the
 * surviving document carries the union.
 * </p>
 */
public class SlackDataStoreFileRoleOrderingTest extends UnitDsTestCase {

    private static final String PUBLIC_CHANNEL = "general";

    private static final String PRIVATE_CHANNEL = "secret";

    private static final String MEMBER_EMAIL = "alice@example.com";

    private static final String FILE_URL = "https://example.slack.com/files/F1";

    /**
     * How long the public channel's file task is held between its merge and its store. Reached
     * only when the two are one critical section, so this is what a passing run waits; it needs
     * to cover no more than the other task's step from a localhost download to the monitor.
     */
    private static final long HOLD_SECONDS = 2;

    /** Only ever waited out when something has already gone wrong; generous on purpose. */
    private static final long GATE_SECONDS = 30;

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    /** Counted down by the public channel's file task once it has merged its roles. */
    private final CountDownLatch publicFileMerged = new CountDownLatch(1);

    /** Counted down once the private channel's copy of the file has been stored. */
    private final CountDownLatch privateFileStored = new CountDownLatch(1);

    /** Guards the hold below, so only the first script evaluation of that task blocks. */
    private final AtomicBoolean publicFileTaskHeld = new AtomicBoolean();

    /** The thread each {@code callback.store} ran on, in call order. */
    private final List<String> storeThreads = Collections.synchronizedList(new ArrayList<>());

    /** Whether the private channel's walk actually observed the public channel's merge. */
    private volatile boolean gateObservedTheMerge;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        server.setStrict(true);

        // See SlackDataStorePermissionSyncTest#setUp for why PermissionHelper is wired by hand,
        // and why the local is not named `systemHelper`.
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

        // The same dotted-path resolver SlackDataStorePermissionSyncTest uses, with the hold
        // described in this class's javadoc bolted on: a script evaluation is the first thing a
        // file task does after merging its roles and the last thing it does before storing them.
        final ScriptEngineFactory scriptEngineFactory = new ScriptEngineFactory();
        scriptEngineFactory.add("groovy", (template, resultMap) -> {
            holdPublicChannelTaskBetweenMergeAndStore(resultMap);
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

        dataStore = new SlackDataStore() {
            @Override
            protected List<String> computeChannelRoles(final SlackClient client, final DataStoreParams paramMap,
                    final Map<String, Object> defaultDataMap, final DataConfig dataConfig, final Channel channel,
                    final AtomicInteger skippedChannelCount, final AtomicInteger unresolvedMemberCount) {
                // Runs on the crawl's own thread, before this channel's files are dispatched, so
                // holding it here holds the whole private-channel walk -- and only it.
                if (channel.isPrivate()) {
                    gateObservedTheMerge = await(publicFileMerged, GATE_SECONDS);
                }
                return super.computeChannelRoles(client, paramMap, defaultDataMap, dataConfig, channel, skippedChannelCount,
                        unresolvedMemberCount);
            }
        };
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        // Never leave a worker parked on a latch a failed assertion will never count down.
        publicFileMerged.countDown();
        privateFileStored.countDown();
        server.stop();
        super.tearDown(testInfo);
    }

    @Test
    public void test_lastStoreOfASharedFileCarriesTheFullestRoleUnion() {
        server.enqueue("/api/users.list", usersListJson(userJson("U1", MEMBER_EMAIL)));
        // Walk order matters: the public channel (no roles of its own) must be walked first, so
        // the union it merges is the smaller one and storing it last is the losing outcome.
        server.enqueue("/api/conversations.list",
                channelsListJson(channelJson("C1", PUBLIC_CHANNEL, false), channelJson("C2", PRIVATE_CHANNEL, true)));
        server.enqueue("/api/team.info", teamInfoJson());
        server.enqueue("/api/conversations.members", membersJson("U1"));
        // No messages in either channel: the file documents are then the only ones evaluated, so
        // the script-engine hook cannot be tripped by anything else.
        server.enqueue("/api/conversations.history", historyJson());
        server.enqueue("/api/conversations.history", historyJson());
        server.enqueue("/api/files.list", filesListJson(fileJson("F1", FILE_URL)));
        server.enqueue("/api/files.list", filesListJson(fileJson("F1", FILE_URL)));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("permission_sync", "true");
        paramMap.put("include_private", "true");
        paramMap.put("file_crawl", "true");
        paramMap.put("number_of_threads", "2");

        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("url", "message.permalink");
        scriptMap.put("captured_roles", "message.roles");
        scriptMap.put("captured_channel", "message.channel");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback() {
            @Override
            public void store(final DataStoreParams storeParams, final Map<String, Object> dataMap) {
                super.store(storeParams, dataMap);
                storeThreads.add(Thread.currentThread().getName());
                if (PRIVATE_CHANNEL.equals(dataMap.get("captured_channel"))) {
                    privateFileStored.countDown();
                }
            }
        };
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        server.assertAllConsumed();
        assertTrue("the private channel's walk must have waited for the public channel's file task to merge; "
                + "without that the two tasks never overlapped and this test proves nothing", gateObservedTheMerge);
        assertTrue("the public channel's file task must have been held between its merge and its store", publicFileTaskHeld.get());

        final List<Map<String, Object>> storedFile =
                callback.getDataMaps().stream().filter(m -> FILE_URL.equals(m.get("url"))).collect(Collectors.toList());
        assertEquals("both channels' files.list walks must have stored this shared file", 2, storedFile.size());
        final Set<String> distinctThreads = new LinkedHashSet<>(storeThreads);
        assertEquals("the two copies must have been stored from different threads, or there was no race to order: " + storeThreads, 2,
                distinctThreads.size());

        @SuppressWarnings("unchecked")
        final List<String> lastStoredRoles = (List<String>) storedFile.get(storedFile.size() - 1).get("captured_roles");
        final String memberRole = ComponentUtil.getFessConfig().getRoleSearchUserPrefix() + MEMBER_EMAIL;
        assertTrue(
                "the surviving document must carry the private channel's member role: the store landing last has to be the one "
                        + "carrying the fullest union, whichever task merged first. Stored roles in order: "
                        + storedFile.stream().map(m -> m.get("captured_roles")).collect(Collectors.toList()),
                lastStoredRoles != null && lastStoredRoles.contains(memberRole));
    }

    /**
     * Holds the public channel's file task where the defect lived -- after its roles are merged,
     * before its document is stored -- until the private channel's task has stored its own copy.
     * Only the first script evaluation of that task blocks; the private channel's task, and every
     * later evaluation, runs straight through.
     *
     * @param resultMap the map handed to the script engine, carrying the document being evaluated
     */
    private void holdPublicChannelTaskBetweenMergeAndStore(final Map<String, Object> resultMap) {
        if (!(resultMap.get("message") instanceof Map<?, ?> message) || !PUBLIC_CHANNEL.equals(message.get("channel"))
                || !publicFileTaskHeld.compareAndSet(false, true)) {
            return;
        }
        publicFileMerged.countDown();
        await(privateFileStored, HOLD_SECONDS);
    }

    /**
     * Waits for a latch, treating a timeout as an answer rather than a failure: the hold above
     * times out by design once merge and store can no longer be interleaved.
     *
     * @param latch the latch to wait on
     * @param seconds how long to wait
     * @return whether the latch fired within that time
     */
    private boolean await(final CountDownLatch latch, final long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private SlackApiMockServer.MockResponse usersListJson(final String... userJsons) {
        return SlackApiMockServer
                .json("{\"ok\":true,\"members\":[" + String.join(",", userJsons) + "],\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private String userJson(final String id, final String email) {
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

    private SlackApiMockServer.MockResponse historyJson() {
        return SlackApiMockServer.json("{\"ok\":true,\"messages\":[],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private SlackApiMockServer.MockResponse membersJson(final String... ids) {
        return SlackApiMockServer.json(
                "{\"ok\":true,\"members\":[\"" + String.join("\",\"", ids) + "\"]," + "\"response_metadata\":{\"next_cursor\":\"\"}}");
    }

    private SlackApiMockServer.MockResponse filesListJson(final String... fileJsons) {
        return SlackApiMockServer.json("{\"ok\":true,\"files\":[" + String.join(",", fileJsons) + "],\"paging\":{\"count\":"
                + fileJsons.length + ",\"total\":" + fileJsons.length + ",\"page\":1,\"pages\":1}}");
    }

    // url_private_download points at this mock server's own "/api/" context, so the download is
    // answered by the strict-mode unscripted response rather than needing a stub of its own.
    private String fileJson(final String id, final String permalink) {
        return "{\"id\":\"" + id + "\",\"permalink\":\"" + permalink + "\",\"mimetype\":\"text/plain\",\"size\":10,"
                + "\"name\":\"f.txt\",\"url_private_download\":\"" + server.getEndpoint() + "download/" + id + "\"}";
    }
}
