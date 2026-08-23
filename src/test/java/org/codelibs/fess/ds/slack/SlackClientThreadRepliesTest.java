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

    private SlackClient newClient() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return server.newClient(paramMap);
    }
}
