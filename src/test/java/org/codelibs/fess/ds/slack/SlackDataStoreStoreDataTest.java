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
 * Exercises {@code storeData} end to end -- through {@code processChannelMessages} and the real
 * {@code processMessage}, not the extracted helpers those are unit-tested at elsewhere in this
 * module -- against {@link SlackApiMockServer}.
 *
 * <p>
 * {@code storeData} drives {@link CrawlerStatsHelper} (which in turn needs {@link SystemHelper})
 * and, on the error path, {@link FailureUrlService}. An earlier commit on this branch removed
 * those component registrations from {@link SlackDataStoreTest#setUp} because none of that
 * class's tests needed them, and because {@code SystemHelper.init()} throws {@code
 * ComponentNotFound: systemProperties} against this module's {@code test_app.xml}. This class
 * registers minimal instances of its own -- scoped to this test class, not globally -- and does
 * not call {@code SystemHelper.init()}, matching the pattern in {@code fess-ds-example}'s {@code
 * ExampleDataStoreTest}.
 * </p>
 *
 * <p>
 * {@code convertValue} evaluates a scriptMap template that is not a literal top-level key of the
 * resultMap (e.g. {@code "message.text"}) through {@code ComponentUtil.getScriptEngineFactory()},
 * which is not registered by convention in this module's narrow test container. A trivial
 * dot-path-walking {@link org.codelibs.fess.script.ScriptEngine} stands in for a real Groovy
 * engine here -- it is a plain functional object, not a mocking framework -- so the scriptMap
 * mapping used by real Fess crawl configs can be exercised without either dependency.
 * </p>
 */
public class SlackDataStoreStoreDataTest extends UnitDsTestCase {

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

        // The error path resolves this via ComponentUtil.getComponent(FailureUrlService.class);
        // the real implementation needs OpenSearch, so a no-op stub keeps this a self-contained
        // unit test. A well-formed run below never reaches it, but a mapping mistake in this
        // test would otherwise fail with a ComponentNotFoundException instead of a legible
        // assertion failure.
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());

        // A minimal ScriptEngine that walks a dot-separated path through nested Maps, standing
        // in for the real Groovy engine that evaluates templates like "message.text" against
        // the resultMap in production.
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

    /**
     * Proves the script-to-index-field mapping works end to end for a channel message, and that
     * the token is genuinely withheld from the script map by the real {@code processMessage} --
     * not merely by {@code newResultMap} in isolation, as {@link SlackDataStoreSecretsTest}
     * checks. The scriptMap here maps a field directly to the literal parameter key {@code
     * "token"}: if it ever reached the resultMap, {@code convertValue}'s {@code
     * containsKey(template)} fast path would return it verbatim, without even touching the
     * script engine stub.
     */
    @Test
    public void test_storeDataMapsMessageFieldsAndWithholdsToken() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"Hello there\",\"ts\":\"1111111111.000100\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"}],\"has_more\":false,"
                        + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-SECRET");
        paramMap.put("channels", "general");

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text");
        scriptMap.put(fessConfig.getIndexFieldUrl(), "message.permalink");
        scriptMap.put("channel_name", "message.channel");
        scriptMap.put("leaked_token", "token");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        assertEquals(1, callback.size());
        final Map<String, Object> dataMap = callback.getDataMaps().get(0);
        assertEquals("Hello there", dataMap.get(fessConfig.getIndexFieldContent()));
        assertEquals("https://example.slack.com/archives/C1/p1111111111000100", dataMap.get(fessConfig.getIndexFieldUrl()));
        assertEquals("general", dataMap.get("channel_name"));
        assertNull("the token must not reach the index even through the real processMessage", dataMap.get("leaked_token"));
        // Pins the isThreadParent guard at its call site in processChannelMessages. The lone
        // message above carries no thread_ts, so it is not a thread parent and its replies
        // must not be fetched; without the guard every message would be re-fetched as a
        // thread, which SlackDataStoreThreadTest cannot catch because it only exercises the
        // predicate in isolation.
        assertEquals(0, server.getRequestCount("/api/conversations.replies"));

        server.assertAllConsumed();
    }
}
