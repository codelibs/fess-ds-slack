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
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;

/**
 * Covers the four single-object lookups that {@code SlackClient.handleApiError} did not reach:
 * the {@code users.info}, {@code bots.info} and {@code conversations.info} cache loaders, and
 * {@code chat.getPermalink}.
 *
 * <p>
 * Slack reports most failures as HTTP 200 with {@code {"ok":false,"error":"..."}}, so an
 * unchecked accessor on those responses simply returns {@code null} and a revoked token is
 * indistinguishable from a genuinely unknown user. Two properties are pinned here: a fatal or
 * transient code must reach the caller as a {@link SlackApiException} -- not as the
 * {@link InvalidCacheLoadException} that every caller already absorbs -- while a genuine
 * lookup miss such as {@code user_not_found} must keep behaving exactly as it did, so an
 * unknown user still falls back to its raw ID instead of failing the crawl.
 * </p>
 */
public class SlackClientLookupErrorTest extends UnitDsTestCase {

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

    // ---- users.info ----

    @Test
    public void test_usersInfo_fatalError_throwsSlackApiException() {
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"token_revoked\"}"));
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> client.getUser("U-unknown"));
        assertEquals("users.info", exception.getMethod());
        assertEquals("token_revoked", exception.getErrorCode());
    }

    /**
     * A rate limit that survived every retry is not a property of the user being looked up, so
     * it must fail the crawl for the same reason it does on the paginated calls.
     */
    @Test
    public void test_usersInfo_transientError_throwsSlackApiException() {
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"ratelimited\"}"));
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> client.getUser("U-unknown"));
        assertEquals("users.info", exception.getMethod());
        assertEquals("ratelimited", exception.getErrorCode());
    }

    /**
     * The whole point of not routing every code through {@link SlackApiException}: a user that
     * really is unknown must still surface as the cache miss its callers already handle by
     * falling back to the raw user ID.
     */
    @Test
    public void test_usersInfo_userNotFound_staysALookupMiss() {
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"user_not_found\"}"));
        Assertions.assertThrows(InvalidCacheLoadException.class, () -> client.getUser("U-unknown"));
    }

    /**
     * The Slack error code behind a lookup miss must be visible above debug. With
     * {@code permission_sync=true} a failing {@code users.info} is what fails a channel closed,
     * and an operator seeing channels skipped needs the reason in the same log.
     */
    @Test
    public void test_usersInfo_lookupMiss_namesTheSlackErrorAtWarn() {
        final TestLogAppender appender = TestLogAppender.attachTo(SlackClient.class);
        try {
            server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"user_not_found\"}"));
            Assertions.assertThrows(InvalidCacheLoadException.class, () -> client.getUser("U-unknown"));
            assertTrue("the Slack error code must be named at warn",
                    appender.messagesAt(Level.WARN).stream().anyMatch(m -> m.contains("users.info") && m.contains("user_not_found")));
        } finally {
            appender.detach();
        }
    }

    // ---- bots.info ----

    @Test
    public void test_botsInfo_fatalError_throwsSlackApiException() {
        server.enqueue("/api/bots.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> client.getBot("B-unknown"));
        assertEquals("bots.info", exception.getMethod());
        assertEquals("invalid_auth", exception.getErrorCode());
    }

    @Test
    public void test_botsInfo_botNotFound_staysALookupMiss() {
        server.enqueue("/api/bots.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"bot_not_found\"}"));
        Assertions.assertThrows(InvalidCacheLoadException.class, () -> client.getBot("B-unknown"));
    }

    // ---- conversations.info ----

    @Test
    public void test_conversationsInfo_fatalError_throwsSlackApiException() {
        server.enqueue("/api/conversations.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"missing_scope\"}"));
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> client.getChannel("C-unknown"));
        assertEquals("conversations.info", exception.getMethod());
        assertEquals("missing_scope", exception.getErrorCode());
    }

    @Test
    public void test_conversationsInfo_channelNotFound_staysALookupMiss() {
        server.enqueue("/api/conversations.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        Assertions.assertThrows(InvalidCacheLoadException.class, () -> client.getChannel("C-unknown"));
    }

    /**
     * {@code getChannels} catches every cache failure so one bad name in the {@code channels}
     * parameter cannot abort the crawl. A fatal error is not a bad name, and must not be
     * absorbed by that catch.
     */
    @Test
    public void test_getChannels_fatalError_escapesTheNameLoop() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        final SlackClient namedClient = server.newClient(paramMap);

        server.enqueue("/api/conversations.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"account_inactive\"}"));
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class, () -> namedClient.getChannels(c -> {}));
        assertEquals("conversations.info", exception.getMethod());
        assertEquals("account_inactive", exception.getErrorCode());
    }

    /** The same loop must still skip a name that simply does not exist. */
    @Test
    public void test_getChannels_channelNotFound_isStillSkipped() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "no-such-channel");
        final SlackClient namedClient = server.newClient(paramMap);

        server.enqueue("/api/conversations.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"channel_not_found\"}"));
        final List<Channel> collected = new ArrayList<>();
        namedClient.getChannels(collected::add);
        assertEquals(0, collected.size());
    }

    // ---- chat.getPermalink ----

    @Test
    public void test_chatGetPermalink_fatalError_throwsSlackApiException() {
        server.enqueue("/api/chat.getPermalink", SlackApiMockServer.json("{\"ok\":false,\"error\":\"not_authed\"}"));
        final SlackApiException exception =
                Assertions.assertThrows(SlackApiException.class, () -> client.getPermalink("C1", "1111111111.000100"));
        assertEquals("chat.getPermalink", exception.getMethod());
        assertEquals("not_authed", exception.getErrorCode());
    }

    /**
     * A message-scoped failure keeps returning {@code null} -- callers substitute their own
     * identifier -- but the Slack error code must no longer be invisible.
     */
    @Test
    public void test_chatGetPermalink_messageScopedError_returnsNullAndWarns() {
        final TestLogAppender appender = TestLogAppender.attachTo(SlackClient.class);
        try {
            server.enqueue("/api/chat.getPermalink", SlackApiMockServer.json("{\"ok\":false,\"error\":\"message_not_found\"}"));
            assertNull(client.getPermalink("C1", "1111111111.000100"));
            assertTrue("the Slack error code must be named at warn",
                    appender.messagesAt(Level.WARN)
                            .stream()
                            .anyMatch(m -> m.contains("chat.getPermalink") && m.contains("message_not_found")));
        } finally {
            appender.detach();
        }
    }

    @Test
    public void test_chatGetPermalink_success_isUnchanged() {
        server.enqueue("/api/chat.getPermalink",
                SlackApiMockServer.json("{\"ok\":true,\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"}"));
        assertEquals("https://example.slack.com/archives/C1/p1111111111000100", client.getPermalink("C1", "1111111111.000100"));
    }
}
