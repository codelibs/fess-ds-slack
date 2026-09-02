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

import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
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
 * Pins where the per-document {@link StatsKeyObject} lives.
 *
 * <p>
 * {@link Constants#CRAWLER_STATS_KEY} identifies one document for statistics and logging. It is
 * not crawl state, so it must not be written to the {@link DataStoreParams} instance that every
 * worker thread shares: {@code processMessage} and {@code processFile} write the key and then
 * read it back one {@code callback.store} later, and between those two points another worker
 * can overwrite it. {@code newStatsParams} moves the key onto a per-document copy.
 * </p>
 *
 * <p>
 * These tests do not need {@code number_of_threads > 1} to be meaningful, and deliberately do
 * not use it: a race reproduces unreliably, whereas "the shared map was never written to" and
 * "each document brought its own instance" are exact properties that hold at any thread count
 * and fail deterministically if the direct {@code paramMap.put} ever comes back.
 * </p>
 *
 * <p>
 * The container setup mirrors {@link SlackDataStoreStoreDataTest}, including its dot-path
 * {@code ScriptEngine} stub; see that class for why each registration is needed.
 * </p>
 */
public class SlackDataStoreStatsKeyTest extends UnitDsTestCase {

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

    private void enqueueTwoMessages() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[" + "{\"text\":\"first\",\"ts\":\"1111111111.000100\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000100\"},"
                        + "{\"text\":\"second\",\"ts\":\"1111111111.000200\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1111111111000200\"}"
                        + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
    }

    private DataStoreParams newParamMap() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-SECRET");
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

    /**
     * The shared parameter map comes back from a completed crawl exactly as it went in, and each
     * stored document arrives with its own copy carrying its own stats key.
     */
    @Test
    public void test_eachDocumentGetsItsOwnParamsAndTheSharedMapIsUntouched() {
        enqueueTwoMessages();

        final DataStoreParams paramMap = newParamMap();
        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, newScriptMap(), new HashMap<>());

        assertNull("the stats key must never be written to the map the worker threads share", paramMap.get(Constants.CRAWLER_STATS_KEY));

        assertEquals(2, callback.size());
        final List<DataStoreParams> stored = callback.getParamMaps();
        assertEquals("every stored document contributes one parameter instance", 2, stored.size());
        assertNotSame("the two documents must not share one instance", stored.get(0), stored.get(1));

        final String[] expectedIds =
                { "https://example.slack.com/archives/C1/p1111111111000100", "https://example.slack.com/archives/C1/p1111111111000200" };
        for (int i = 0; i < stored.size(); i++) {
            final DataStoreParams localParams = stored.get(i);
            assertNotSame("callback.store must receive the copy, not the shared instance", paramMap, localParams);

            final Object value = localParams.get(Constants.CRAWLER_STATS_KEY);
            assertNotNull("the copy must still carry the stats key the callback contract expects", value);
            assertTrue("the stats key must be a StatsKeyObject, not its toString", value instanceof StatsKeyObject);
            assertEquals("the stats key must identify this document", expectedIds[i], ((StatsKeyObject) value).getId());

            // newInstance() copies the contents, so the ordinary parameters a callback or an
            // ingester reads are still present -- the copy is not an empty map with one key.
            assertEquals("the copy must carry the ordinary parameters too", "general", localParams.getAsString("channels"));
        }

        server.assertAllConsumed();
    }

    /**
     * The stats key is no longer visible to crawl scripts.
     *
     * <p>
     * {@code newResultMap} copies the shared parameter map into the script scope, so while the
     * key was written there it was copied along with it, and under {@code number_of_threads > 1}
     * the instance a script found could have belonged to a different document. Reaching it from
     * real Groovy was only ever theoretical -- {@code "crawler.stats.key"} contains dots, so the
     * name resolves as property navigation rather than as a binding -- but {@code convertValue}
     * returns a value verbatim when the template matches a resultMap key exactly, which is a
     * path no script syntax is needed for. That is the path this asserts is now closed.
     * </p>
     */
    @Test
    public void test_statsKeyNoLongerReachesTheScriptScope() {
        enqueueTwoMessages();

        final Map<String, String> scriptMap = newScriptMap();
        scriptMap.put("stats_leak", Constants.CRAWLER_STATS_KEY);

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, newParamMap(), scriptMap, new HashMap<>());

        assertEquals(2, callback.size());
        for (final Map<String, Object> dataMap : callback.getDataMaps()) {
            assertNull("internal crawl plumbing must not be indexable", dataMap.get("stats_leak"));
        }

        server.assertAllConsumed();
    }

    /**
     * {@code newStatsParams} copies rather than mutates, which is the whole property the two
     * end-to-end tests above depend on.
     */
    @Test
    public void test_newStatsParamsCopiesRatherThanMutating() {
        final DataStoreParams paramMap = newParamMap();
        final StatsKeyObject statsKey = new StatsKeyObject("https://example.slack.com/archives/C1/p1");

        final DataStoreParams localParams = dataStore.newStatsParams(paramMap, statsKey);

        assertNotSame("a copy, not the same instance", paramMap, localParams);
        assertNull("the original must not gain the key", paramMap.get(Constants.CRAWLER_STATS_KEY));
        assertSame("the copy carries the key given to it", statsKey, localParams.get(Constants.CRAWLER_STATS_KEY));
        assertEquals("the copy carries the original's entries", "general", localParams.getAsString("channels"));

        // A second copy of the same original is independent of the first, which is what makes
        // concurrent workers safe rather than merely differently ordered.
        final StatsKeyObject other = new StatsKeyObject("https://example.slack.com/archives/C1/p2");
        final DataStoreParams otherParams = dataStore.newStatsParams(paramMap, other);
        assertSame("the first copy keeps its own key", statsKey, localParams.get(Constants.CRAWLER_STATS_KEY));
        assertSame("the second copy carries its own key", other, otherParams.get(Constants.CRAWLER_STATS_KEY));
    }
}
