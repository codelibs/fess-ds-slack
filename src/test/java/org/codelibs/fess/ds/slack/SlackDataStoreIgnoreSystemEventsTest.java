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
import java.util.List;
import java.util.Map;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
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
 * Covers C25: no message-{@code subtype} filter existed, so {@code channel_join}/{@code
 * channel_leave} and other Slack-generated channel-administration notifications were indexed as
 * documents -- search noise, and content four of the connectors surveyed in the design spec
 * exclude by default.
 *
 * <p>
 * <b>This is the one parameter in Phase 2's operability PR that changes indexed content</b>:
 * {@code ignore_system_events} defaults to {@code true}, so a crawl run without setting it
 * explicitly now indexes fewer documents than before this PR.
 * </p>
 */
public class SlackDataStoreIgnoreSystemEventsTest extends UnitDsTestCase {

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

        // A trivial dot-path-walking ScriptEngine standing in for the real Groovy engine, so a
        // scriptMap template like "message.text" can be evaluated without that dependency; see
        // SlackDataStoreStoreDataTest's class javadoc for the same pattern.
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

    private void enqueueChannelHistory() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history", SlackApiMockServer.json("{\"ok\":true,\"messages\":["
                + "{\"text\":\"has joined the channel\",\"subtype\":\"channel_join\",\"ts\":\"1111111111.000100\","
                + "\"permalink\":\"https://example.slack.com/archives/C1/p1\"},"
                + "{\"text\":\"a real broadcast reply\",\"subtype\":\"thread_broadcast\",\"ts\":\"1111111111.000200\","
                + "\"permalink\":\"https://example.slack.com/archives/C1/p2\"}," + "{\"text\":\"hello team\",\"ts\":\"1111111111.000300\","
                + "\"permalink\":\"https://example.slack.com/archives/C1/p3\"}"
                + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
    }

    /**
     * Default (unset) must drop {@code channel_join} but keep the plain message and the {@code
     * thread_broadcast} one -- {@code thread_broadcast} is a real message broadcast to the
     * channel, not a system event, and must not be treated as one.
     */
    @Test
    public void test_defaultIgnoresChannelJoinButKeepsThreadBroadcast() {
        enqueueChannelHistory();

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        final List<Map<String, Object>> dataMaps = callback.getDataMaps();
        assertEquals("channel_join must be dropped by default", 2, dataMaps.size());
        assertTrue("the thread_broadcast message must still be indexed",
                dataMaps.stream().anyMatch(m -> "a real broadcast reply".equals(m.get(fessConfig.getIndexFieldContent()))));
        assertTrue("the plain message must still be indexed",
                dataMaps.stream().anyMatch(m -> "hello team".equals(m.get(fessConfig.getIndexFieldContent()))));
        assertTrue("channel_join must not be indexed",
                dataMaps.stream().noneMatch(m -> "has joined the channel".equals(m.get(fessConfig.getIndexFieldContent()))));
    }

    /** {@code ignore_system_events=false} must restore indexing every message, unfiltered. */
    @Test
    public void test_ignoreSystemEventsFalseKeepsAllMessages() {
        enqueueChannelHistory();

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        paramMap.put("ignore_system_events", "false");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        assertEquals(3, callback.size());
    }
}
