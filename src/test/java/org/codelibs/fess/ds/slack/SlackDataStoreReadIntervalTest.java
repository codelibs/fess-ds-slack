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
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers {@code read_interval}: {@link org.codelibs.fess.ds.AbstractDataStore#getReadInterval}
 * and {@link org.codelibs.fess.ds.AbstractDataStore#sleep} exist but this plugin never called
 * them, so there was no way to pace a crawl against a rate-limited workspace. {@code
 * fess-ds-db}'s {@code DatabaseDataStore} sleeps once per processed row; this applies the same
 * pattern once per processed message and once per processed file.
 *
 * <p>
 * {@link SlackDataStore#getReadInterval} overrides the inherited method rather than relying on
 * it directly: {@code AbstractDataStore.getReadInterval} reads the hardcoded key {@code
 * "readInterval"} (camelCase), which does not match this plugin's own snake_case convention
 * (token, include_private, connection_timeout, exclude_archived, ...) or the {@code
 * read_interval} name documented for this parameter.
 * </p>
 */
public class SlackDataStoreReadIntervalTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();

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
                SlackApiMockServer.json("{\"ok\":true,\"messages\":["
                        + "{\"text\":\"hi\",\"ts\":\"1111111111.000100\",\"permalink\":\"https://example.slack.com/archives/C1/p1\"},"
                        + "{\"text\":\"bye\",\"ts\":\"1111111111.000200\",\"permalink\":\"https://example.slack.com/archives/C1/p2\"}"
                        + "],\"has_more\":false,\"response_metadata\":{\"next_cursor\":\"\"}}"));
    }

    /** Unset must leave current behaviour unchanged: {@code sleep} is never called. */
    @Test
    public void test_readIntervalDefaultsToZero_neverSleeps() {
        enqueueTwoMessages();

        final List<Long> sleptIntervals = new CopyOnWriteArrayList<>();
        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected void sleep(final long interval) {
                sleptIntervals.add(interval);
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

        assertTrue("read_interval unset must never sleep", sleptIntervals.isEmpty());
    }

    /** A positive {@code read_interval} must sleep once per processed message, for that long. */
    @Test
    public void test_readIntervalSleepsOnceAfterEachMessage() {
        enqueueTwoMessages();

        final List<Long> sleptIntervals = new CopyOnWriteArrayList<>();
        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected void sleep(final long interval) {
                sleptIntervals.add(interval);
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        paramMap.put("read_interval", "250");

        dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>());

        assertEquals(2, sleptIntervals.size());
        assertEquals(new ArrayList<>(List.of(250L, 250L)), new ArrayList<>(sleptIntervals));
    }
}
