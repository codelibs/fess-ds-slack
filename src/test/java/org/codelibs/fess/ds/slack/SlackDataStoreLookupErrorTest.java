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

import java.util.HashMap;
import java.util.Map;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.files.FilesListRequest;
import org.codelibs.fess.ds.slack.api.method.files.FilesListResponse;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.script.ScriptEngineFactory;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * The data-store half of the single-object lookup fix: a fatal Slack error raised by
 * {@code users.info}, {@code bots.info}, {@code conversations.info} or {@code chat.getPermalink}
 * has to survive the broad catches this class wraps every lookup in, and reach {@code storeData}
 * so the job fails instead of finishing green with raw user IDs in the author field.
 *
 * <p>
 * The container setup mirrors {@link SlackDataStoreStatsKeyTest}, including its dot-path
 * {@code ScriptEngine} stub; see that class for why each registration is needed.
 * </p>
 */
public class SlackDataStoreLookupErrorTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
        dataStore = new SlackDataStore();

        ComponentUtil.register(new SystemHelper(), "systemHelper");
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

    // ---- The per-lookup helpers must not absorb a fatal error ----

    /**
     * {@code getUsername} falls back to the raw user ID on a lookup miss, which is right for an
     * unknown user and wrong for a revoked token: the crawl would keep indexing documents whose
     * author is an ID and still report success.
     */
    @Test
    public void test_getUsername_fatalErrorPropagates() {
        final SlackClient client = server.newClient("xoxb-test");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"token_revoked\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> dataStore.getUsername(client, "U1"));
    }

    /** An unknown user must still fall back to its ID rather than failing the crawl. */
    @Test
    public void test_getUsername_lookupMissStillFallsBackToTheId() {
        final SlackClient client = server.newClient("xoxb-test");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"user_not_found\"}"));
        assertEquals("U1", dataStore.getUsername(client, "U1"));
    }

    /** {@code getMessageUsername}'s catch-all is the widest of the three; it must let this out. */
    @Test
    public void test_getMessageUsername_fatalErrorPropagates() {
        final SlackClient client = server.newClient("xoxb-test");
        final Message message = firstMessage("{\"ok\":true,\"messages\":[{\"text\":\"hi\",\"ts\":\"1.0\",\"user\":\"U1\"}]}");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> dataStore.getMessageUsername(client, message));
    }

    /** The same catch-all, reached through {@code bots.info} instead of {@code users.info}. */
    @Test
    public void test_getMessageUsername_botLookupFatalErrorPropagates() {
        final SlackClient client = server.newClient("xoxb-test");
        final Message message =
                firstMessage("{\"ok\":true,\"messages\":[{\"text\":\"hi\",\"ts\":\"1.0\",\"subtype\":\"bot_message\",\"bot_id\":\"B1\"}]}");
        server.enqueue("/api/bots.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> dataStore.getMessageUsername(client, message));
    }

    @Test
    public void test_getFileUsername_fatalErrorPropagates() {
        final SlackClient client = server.newClient("xoxb-test");
        final File file = firstFile("{\"ok\":true,\"files\":[{\"id\":\"F1\",\"name\":\"a.txt\",\"user\":\"U1\"}]}");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"invalid_auth\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> dataStore.getFileUsername(client, file));
    }

    /**
     * {@code permission_sync}'s member-email resolution swallows every lookup failure at debug
     * and fails the channel closed, which hides a revoked token behind "this channel has no
     * resolvable members".
     */
    @Test
    public void test_getMemberEmail_fatalErrorPropagates() {
        final SlackClient client = server.newClient("xoxb-test");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"missing_scope\"}"));
        Assertions.assertThrows(SlackApiException.class, () -> dataStore.getMemberEmail(client, "U1"));
    }

    /** A member who cannot be resolved must still fail closed rather than fail the crawl. */
    @Test
    public void test_getMemberEmail_lookupMissStillReturnsNull() {
        final SlackClient client = server.newClient("xoxb-test");
        server.enqueue("/api/users.info", SlackApiMockServer.json("{\"ok\":false,\"error\":\"user_not_found\"}"));
        assertNull(dataStore.getMemberEmail(client, "U1"));
    }

    // ---- chat.getPermalink, end to end ----

    /**
     * A message-scoped {@code chat.getPermalink} failure used to return {@code null} all the way
     * into {@code new StatsKeyObject(null)}, which made {@code CrawlerStatsHelper#begin} throw
     * and the message be recorded as a failure carrying an error that says nothing about Slack.
     * The message must instead be indexed under the same channel/timestamp identifier
     * {@code processMessage} already substitutes when the lookup throws.
     */
    @Test
    public void test_permalinkFailure_stillIndexesTheMessage() {
        enqueueOneMessageWithoutPermalink();
        server.enqueue("/api/chat.getPermalink", SlackApiMockServer.json("{\"ok\":false,\"error\":\"message_not_found\"}"));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, newParamMap(), newScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        assertEquals("C1/1111111111.000100", callback.getDataMaps().get(0).get(fessConfig.getIndexFieldUrl()));
    }

    /** A fatal error on the same call must fail the whole crawl instead. */
    @Test
    public void test_permalinkFatalError_failsTheCrawl() {
        enqueueOneMessageWithoutPermalink();
        server.enqueue("/api/chat.getPermalink", SlackApiMockServer.json("{\"ok\":false,\"error\":\"token_revoked\"}"));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), callback, newParamMap(), newScriptMap(), new HashMap<>()));
        assertEquals("chat.getPermalink", exception.getMethod());
        assertEquals("token_revoked", exception.getErrorCode());
    }

    private void enqueueOneMessageWithoutPermalink() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        // No team.info stub: the default response body carries no `team`, so getTeam() returns
        // null and the permalink is resolved per message via chat.getPermalink -- which is the
        // only way to reach the call under test.
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"first\",\"ts\":\"1111111111.000100\"}],"
                        + "\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
    }

    private DataStoreParams newParamMap() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        return paramMap;
    }

    private Map<String, String> newScriptMap() {
        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text");
        scriptMap.put(fessConfig.getIndexFieldUrl(), "message.permalink");
        return scriptMap;
    }

    private Message firstMessage(final String content) {
        return new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class).getMessages().get(0);
    }

    private File firstFile(final String content) {
        return new FilesListRequest(null).parseResponse(content, FilesListResponse.class).getFiles().get(0);
    }
}
