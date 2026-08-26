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
import java.util.concurrent.atomic.AtomicBoolean;

import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers C21: none of {@link SlackClient}'s paging loops ever consulted {@code alive}, so a
 * Slack crawl could not be stopped from the admin UI -- it ran until every page of every
 * loop was exhausted. This exercises the {@code BooleanSupplier aliveSupplier} constructor
 * overload directly (see {@link SlackApiMockServer#newClient(DataStoreParams,
 * java.util.function.BooleanSupplier)}), proving each loop stops after finishing the page it is
 * on rather than fetching another.
 */
public class SlackClientAliveTest extends UnitDsTestCase {

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
     * The constructor's {@code users.list} preload must not fetch a second page once {@code
     * alive} reports false, even though a second page is queued and ready.
     */
    @Test
    public void test_usersListPreloadStopsWhenNotAlive() {
        server.enqueue("/api/users.list", SlackApiMockServer.json(
                "{\"ok\":true,\"members\":[{\"id\":\"U1\",\"name\":\"alice\"}],\"response_metadata\":{\"next_cursor\":\"cursor1\"}}"));
        server.enqueue("/api/users.list", SlackApiMockServer
                .json("{\"ok\":true,\"members\":[{\"id\":\"U2\",\"name\":\"bob\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");

        server.newClient(paramMap, () -> false);

        assertEquals(1, server.getRequestCount("/api/users.list"));
        assertEquals("the second page must be left unconsumed", 1, server.getQueuedCount("/api/users.list"));
    }

    /**
     * The constructor's {@code conversations.list} preload must not fetch a second page once
     * {@code alive} reports false.
     */
    @Test
    public void test_conversationsListPreloadStopsWhenNotAlive() {
        server.enqueue("/api/conversations.list", SlackApiMockServer.json(
                "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"cursor1\"}}"));
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C2\",\"name\":\"random\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");

        server.newClient(paramMap, () -> false);

        assertEquals(1, server.getRequestCount("/api/conversations.list"));
        assertEquals("the second page must be left unconsumed", 1, server.getQueuedCount("/api/conversations.list"));
    }

    /** {@code conversations.history} paging must stop once {@code alive} flips false mid-walk. */
    @Test
    public void test_channelMessagesPagingStopsWhenNotAlive() {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final SlackClient client = server.newClient("xoxb-test", alive::get);
        // The constructor preload above already consumed the default users.list/conversations.list
        // responses; flip alive only now so the assertions below are about the history walk, not
        // the preload covered separately.
        alive.set(false);

        server.enqueue("/api/conversations.history", SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"hi\","
                + "\"ts\":\"1.0\"}],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"cursor1\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"bye\",\"ts\":\"2.0\"}],\"has_more\":false,"
                        + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> messages = new ArrayList<>();
        client.getChannelMessages("C1", messages::add);

        assertEquals(1, messages.size());
        assertEquals(1, server.getRequestCount("/api/conversations.history"));
        assertEquals("the second page must be left unconsumed", 1, server.getQueuedCount("/api/conversations.history"));
    }

    /** {@code conversations.replies} paging must stop once {@code alive} flips false mid-walk. */
    @Test
    public void test_messageRepliesPagingStopsWhenNotAlive() {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final SlackClient client = server.newClient("xoxb-test", alive::get);
        alive.set(false);

        server.enqueue("/api/conversations.replies",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"parent\",\"ts\":\"1.0\"},"
                        + "{\"text\":\"reply1\",\"ts\":\"1.1\"}],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"cursor1\"}}"));
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"reply2\",\"ts\":\"1.2\"}],\"has_more\":false,"
                        + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        client.getMessageReplies("C1", "1.0", replies::add);

        assertEquals(1, replies.size());
        assertEquals(1, server.getRequestCount("/api/conversations.replies"));
        assertEquals("the second page must be left unconsumed", 1, server.getQueuedCount("/api/conversations.replies"));
    }

    /** {@code files.list} paging must stop once {@code alive} flips false mid-walk. */
    @Test
    public void test_channelFilesPagingStopsWhenNotAlive() {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final SlackClient client = server.newClient("xoxb-test", alive::get);
        alive.set(false);

        server.enqueue("/api/files.list", SlackApiMockServer
                .json("{\"ok\":true,\"files\":[{\"id\":\"F1\"}]," + "\"paging\":{\"count\":1,\"total\":2,\"page\":1,\"pages\":2}}"));
        server.enqueue("/api/files.list", SlackApiMockServer
                .json("{\"ok\":true,\"files\":[{\"id\":\"F2\"}]," + "\"paging\":{\"count\":1,\"total\":2,\"page\":2,\"pages\":2}}"));

        final List<String> fileIds = new ArrayList<>();
        client.getChannelFiles("C1", file -> fileIds.add(file.getId()));

        assertEquals(1, fileIds.size());
        assertEquals(1, server.getRequestCount("/api/files.list"));
        assertEquals("the second page must be left unconsumed", 1, server.getQueuedCount("/api/files.list"));
    }

    /**
     * Confirms the always-alive overload still exists and behaves exactly as before: nothing in
     * this PR may change the meaning of {@code new SlackClient(paramMap)} / {@code
     * server.newClient(paramMap)} for the many existing tests that use it.
     */
    @Test
    public void test_backwardCompatibleConstructorNeverStopsEarly() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");

        final List<Channel> channels = new ArrayList<>();
        server.newClient(paramMap).getAllChannels(channels::add);

        assertEquals(1, channels.size());
    }
}
