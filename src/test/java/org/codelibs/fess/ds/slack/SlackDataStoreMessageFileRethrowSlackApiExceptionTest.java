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

import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers Important-2 from the Phase 2 whole-branch review: {@code processMessage} and {@code
 * processFile} each end in {@code catch (final Throwable t)}, and without an explicit rethrow a
 * {@link SlackApiException} raised from inside either method would be caught as a plain {@code
 * Throwable}, recorded via {@code FailureUrlService} as an ordinary per-item failure, and the
 * crawl would continue -- reporting a false success on a token that can no longer authenticate at
 * all. The rethrow was defensive when written, because nothing inside either method routed
 * through {@code SlackClient#handleApiError} yet; the single-object lookups (users.info,
 * bots.info, conversations.info, chat.getPermalink) now do, so it guards a live path. See
 * {@link SlackDataStoreLookupErrorTest} for the lookups reaching it through their real calls.
 *
 * <p>
 * Drives {@code storeData} end to end (through the real worker-thread dispatch in {@code
 * processChannelMessages}/{@code processChannelFiles}), injecting a {@link SlackApiException}
 * from a method each calls unguarded -- {@code getMessageText}/{@code getFileTitle} -- so the
 * only thing under test is whether {@code processMessage}/{@code processFile} let it propagate
 * instead of swallowing it. This fails against the pre-fix code: {@code storeData} returns
 * normally instead of throwing.
 * </p>
 */
public class SlackDataStoreMessageFileRethrowSlackApiExceptionTest extends UnitDsTestCase {

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

    @Test
    public void test_processMessage_rethrowsSlackApiExceptionInsteadOfRecordingIt() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/conversations.history",
                SlackApiMockServer.json("{\"ok\":true,\"messages\":[{\"text\":\"hi\",\"ts\":\"1.0\","
                        + "\"permalink\":\"https://example.slack.com/archives/C1/p1\"}],\"has_more\":false,"
                        + "\"response_metadata\":{\"next_cursor\":\"\"}}"));

        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected String getMessageText(final Message message) {
                throw new SlackApiException("users.info", "invalid_auth");
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");

        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("invalid_auth", exception.getErrorCode());
        assertEquals("users.info", exception.getMethod());
    }

    @Test
    public void test_processFile_rethrowsSlackApiExceptionInsteadOfRecordingIt() {
        server.enqueue("/api/conversations.list", SlackApiMockServer
                .json("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"\"}}"));
        server.enqueue("/api/files.list",
                SlackApiMockServer.json("{\"ok\":true,\"files\":[{\"id\":\"F1\",\"permalink\":\"https://example.slack.com/files/F1\","
                        + "\"url_private_download\":\"" + server.getEndpoint() + "download/F1\",\"mimetype\":\"text/plain\","
                        + "\"size\":10,\"name\":\"f.txt\"}],\"paging\":{\"count\":1,\"total\":1,\"page\":1,\"pages\":1}}"));

        final SlackDataStore dataStore = new SlackDataStore() {
            @Override
            protected String getFileTitle(final File file) {
                throw new SlackApiException("users.info", "invalid_auth");
            }
        };

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("channels", "general");
        paramMap.put("file_crawl", "true");

        final SlackApiException exception = Assertions.assertThrows(SlackApiException.class,
                () -> dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, new HashMap<>(), new HashMap<>()));

        assertEquals("invalid_auth", exception.getErrorCode());
        assertEquals("users.info", exception.getMethod());
    }
}
