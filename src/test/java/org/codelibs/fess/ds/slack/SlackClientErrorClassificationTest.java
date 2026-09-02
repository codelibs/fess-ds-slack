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

import org.apache.logging.log4j.Level;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers the {@code ok: false} classification that {@code SlackClient.handleApiError}
 * centralizes for the five paginated calls (files.list, conversations.list,
 * conversations.history, conversations.replies, users.list): fatal codes must throw
 * {@link SlackApiException} so the job fails instead of reporting a false success; everything
 * else must be skipped without aborting the crawl; {@code thread_not_found} must not be logged
 * at warn; and no code -- including one this table does not name -- may be treated as fatal
 * except the six explicitly listed.
 */
public class SlackClientErrorClassificationTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackClient client;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        client = server.newClient("xoxb-test");
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        server.stop();
        super.tearDown(testInfo);
    }

    // ---- Fatal: every listed code, exercised once against users.list ----

    @Test
    public void test_fatalErrorCodes_throwSlackApiException() {
        final String[] fatalCodes = { "invalid_auth", "token_revoked", "account_inactive", "missing_scope", "not_authed", "token_expired",
                // Not one of Slack's authentication-specific codes, but a property of the
                // credential all the same, so it recurs on every call rather than being
                // scoped to the channel or page being fetched.
                "not_allowed_token_type" };
        for (final String code : fatalCodes) {
            server.enqueue("/api/users.list", SlackApiMockServer.json("{\"ok\":false,\"error\":\"" + code + "\"}"));
            final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> client.getUsers(u -> {}));
            assertEquals("errorCode for " + code, code, exception.getErrorCode());
            assertEquals("method for " + code, "users.list", exception.getMethod());
        }
    }

    // ---- Fatal: wiring confirmation for the other four call sites ----

    @Test
    public void test_conversationsList_fatalError_throwsSlackApiException() {
        server.enqueue("/api/conversations.list", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> client.getAllChannels(100, c -> {}));
    }

    @Test
    public void test_conversationsHistory_fatalError_throwsSlackApiException() {
        server.enqueue("/api/conversations.history", SlackApiMockServer.json("{\"ok\":false,\"error\":\"token_revoked\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> client.getChannelMessages("C1", m -> {}));
    }

    @Test
    public void test_conversationsReplies_fatalError_throwsSlackApiException() {
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json("{\"ok\":false,\"error\":\"missing_scope\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> client.getMessageReplies("C1", "1.0", m -> {}));
    }

    @Test
    public void test_filesList_fatalError_throwsSlackApiException() {
        server.enqueue("/api/files.list", SlackApiMockServer.json("{\"ok\":false,\"error\":\"account_inactive\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> client.getChannelFiles("C1", 20, f -> {}));
    }

    // ---- Channel-scoped skip: warn and continue, one failing channel does not affect the next ----

    @Test
    public void test_channelScopedError_doesNotThrow_andLaterChannelStillProcessed() {
        server.enqueue("/api/conversations.history", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        final List<Message> collectedForFailedChannel = new ArrayList<>();
        client.getChannelMessages("C1", collectedForFailedChannel::add);
        assertEquals(0, collectedForFailedChannel.size());

        // The failure above must not have left the client (or anything it shares across calls,
        // e.g. its caches) in a broken state: an unrelated later channel is processed normally.
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"ts\":\"1.0\"}],\"has_more\":false}"));
        final List<Message> collectedForOtherChannel = new ArrayList<>();
        client.getChannelMessages("C2", collectedForOtherChannel::add);
        assertEquals(1, collectedForOtherChannel.size());
    }

    @Test
    public void test_notInChannel_doesNotThrow() {
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json("{\"ok\":false,\"error\":\"not_in_channel\"}"));
        final List<Message> collected = new ArrayList<>();
        client.getMessageReplies("C1", "1.0", collected::add);
        assertEquals(0, collected.size());
    }

    // ---- Normal-case skip: thread_not_found must not warn ----

    @Test
    public void test_threadNotFound_doesNotThrow() {
        server.enqueue("/api/conversations.replies", SlackApiMockServer.json("{\"ok\":false,\"error\":\"thread_not_found\"}"));
        final List<Message> collected = new ArrayList<>();
        client.getMessageReplies("C1", "1.0", collected::add);
        assertEquals(0, collected.size());
    }

    @Test
    public void test_threadNotFound_isNotLoggedAtWarn() {
        final TestLogAppender appender = TestLogAppender.attachTo(SlackClient.class);
        try {
            server.enqueue("/api/conversations.replies", SlackApiMockServer.json("{\"ok\":false,\"error\":\"thread_not_found\"}"));
            client.getMessageReplies("C1", "1.0", m -> {});
            assertFalse("thread_not_found must not be logged at warn", appender.hasEventAt(Level.WARN));
            assertTrue("thread_not_found must still be visible at debug", appender.hasEventAt(Level.DEBUG));
        } finally {
            appender.detach();
        }
    }

    // ---- Everything else, including unknown codes: warn and skip, never fatal ----

    @Test
    public void test_unknownErrorCode_doesNotThrow() {
        server.enqueue("/api/users.list", SlackApiMockServer.json("{\"ok\":false,\"error\":\"some_future_error_code\"}"));
        final List<User> collected = new ArrayList<>();
        client.getUsers(collected::add);
        assertEquals(0, collected.size());
    }

    // ---- The raw response body must move from warn to debug (closing the rest of C27) ----

    @Test
    public void test_unknownErrorCode_bodyLoggedAtDebugNotWarn() {
        final TestLogAppender appender = TestLogAppender.attachTo(SlackClient.class);
        try {
            final String body = "{\"ok\":false,\"error\":\"some_future_error_code\",\"needle\":\"do-not-warn-me\"}";
            server.enqueue("/api/users.list", SlackApiMockServer.json(body));
            client.getUsers(u -> {});
            assertTrue("the error code must still be visible at warn", appender.hasEventAt(Level.WARN));
            for (final String message : appender.messagesAt(Level.WARN)) {
                assertFalse("the raw response body must not be logged at warn: " + message, message.contains("do-not-warn-me"));
            }
            assertTrue("the raw response body must be available at debug",
                    appender.messagesAt(Level.DEBUG).stream().anyMatch(m -> m.contains("do-not-warn-me")));
        } finally {
            appender.detach();
        }
    }
}
