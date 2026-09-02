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

import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackClientThreadRepliesTest extends UnitDsTestCase {

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
     * A broadcast reply carries {@code subtype: "thread_broadcast"} and must be skipped by
     * {@code getMessageReplies}, or it is stored twice: once via {@code conversations.history},
     * once via this walk. This test fails against the pre-fix guard, {@code
     * message.isThreadBroadcast()}: that getter is always false (the backing field is
     * {@code protected} with no setter, and Jackson's default field visibility is
     * {@code PUBLIC_ONLY}), so the broadcast reply below would incorrectly reach the consumer
     * and {@code replies.size()} would come back {@code 2}, not {@code 1}.
     */
    @Test
    public void test_broadcastReplySkippedBySubtype() {
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":["
                        + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"},"
                        + "{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"text\":\"broadcast reply\",\"subtype\":\"thread_broadcast\"},"
                        + "{\"ts\":\"3.000300\",\"thread_ts\":\"1.000100\",\"text\":\"normal reply\"}"
                        + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        newClient().getMessageReplies("C1", "1.000100", 100, replies::add);

        assertEquals(1, replies.size());
        assertEquals("normal reply", replies.get(0).getText());
    }

    /**
     * A raw {@code thread_broadcast: true} (or {@code is_thread_broadcast: true}) flag with no
     * {@code subtype} must not suppress the reply: only the {@code subtype} is authoritative.
     * This locks in that the fix keys off {@code subtype} specifically, not off any boolean
     * field that happens to deserialize -- guarding against a future "fix" that resurrects a
     * boolean-backed check instead.
     */
    @Test
    public void test_booleanFlagWithoutSubtypeDoesNotSuppressReply() {
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"},"
                                + "{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"text\":\"reply\",\"thread_broadcast\":true,"
                                + "\"is_thread_broadcast\":true}" + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        newClient().getMessageReplies("C1", "1.000100", 100, replies::add);

        assertEquals(1, replies.size());
        assertEquals("reply", replies.get(0).getText());
    }

    /**
     * A thread with more replies than fit on one page must fetch a second page: {@code
     * getMessageReplies}'s paging loop breaks out as soon as {@code response.hasMore()} is
     * false, so this proves the fix end to end -- a unit-level fix to {@code
     * ConversationsRepliesResponse#hasMore} means nothing if the paging loop still stops early
     * for some other reason. Before the fix, {@code hasMore()} always returned {@code false}
     * regardless of the JSON, so only page 1 would ever be requested and this test would fail
     * with {@code repliesRequestCount==1} and {@code replies.size()==2}.
     */
    @Test
    public void test_pagesThroughRepliesWhenHasMoreIsTrue() {
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"},"
                                + "{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"text\":\"reply1\"}"
                                + "],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"CUR\"}}"));
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"},"
                                + "{\"ts\":\"3.000300\",\"thread_ts\":\"1.000100\",\"text\":\"reply2\"}"
                                + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        newClient().getMessageReplies("C1", "1.000100", 100, replies::add);

        assertEquals(2, server.getRequestCount("/api/conversations.replies"));
        assertEquals(2, replies.size());
        assertEquals("reply1", replies.get(0).getText());
        assertEquals("reply2", replies.get(1).getText());
        assertEquals("CUR", server.getRequests("/api/conversations.replies").get(1).get("cursor"));
    }

    /**
     * A second page that does NOT repeat the thread parent must still yield both of its
     * replies. Slack's reference for {@code conversations.replies} does not state whether the
     * parent is returned as {@code messages[0]} of every page or only of the first, so the walk
     * identifies the parent by its timestamp; skipping index 0 unconditionally -- what this
     * class did before -- silently drops the first reply of every page after the first, which
     * is one lost message per page for any thread longer than {@code message_count}.
     */
    @Test
    public void test_pageWithoutRepeatedParentKeepsEveryReply() {
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"},"
                                + "{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"text\":\"reply1\"}"
                                + "],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"CUR\"}}"));
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"3.000300\",\"thread_ts\":\"1.000100\",\"text\":\"reply2\"},"
                                + "{\"ts\":\"4.000400\",\"thread_ts\":\"1.000100\",\"text\":\"reply3\"}"
                                + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        newClient().getMessageReplies("C1", "1.000100", 100, replies::add);

        assertEquals(3, replies.size());
        assertEquals("reply1", replies.get(0).getText());
        assertEquals("reply2", replies.get(1).getText());
        assertEquals("reply3", replies.get(2).getText());
    }

    /**
     * The parent is skipped wherever it appears, not only at index 0.
     */
    @Test
    public void test_parentIsSkippedByTimestampNotByPosition() {
        server.enqueue("/api/conversations.replies",
                SlackApiMockServer
                        .json("{\"ok\":true,\"messages\":[" + "{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"text\":\"reply1\"},"
                                + "{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\",\"text\":\"parent\"}"
                                + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final List<Message> replies = new ArrayList<>();
        newClient().getMessageReplies("C1", "1.000100", 100, replies::add);

        assertEquals(1, replies.size());
        assertEquals("reply1", replies.get(0).getText());
    }

    private SlackClient newClient() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return server.newClient(paramMap);
    }
}
