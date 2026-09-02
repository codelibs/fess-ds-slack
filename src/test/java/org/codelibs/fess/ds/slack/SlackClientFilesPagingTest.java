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
import java.util.Map;

import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackClientFilesPagingTest extends UnitDsTestCase {

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
     * 37 files at count=20 means pages=2. The loop must stop after page 2,
     * not keep going until page reaches total (37).
     */
    @Test
    public void test_stopsAtPagesNotTotal() {
        server.enqueue("/api/files.list", SlackApiMockServer.json(filesPage(1, 2, 37, 20)));
        server.enqueue("/api/files.list", SlackApiMockServer.json(filesPage(2, 2, 37, 17)));

        final List<File> collected = new ArrayList<>();
        newClient().getChannelFiles("C1", 20, collected::add);

        assertEquals(2, server.getRequestCount("/api/files.list"));
        assertEquals(37, collected.size());
    }

    /**
     * The types filter must be sent on every page, not only the first.
     */
    @Test
    public void test_typesFilterAppliedToEveryPage() {
        server.enqueue("/api/files.list", SlackApiMockServer.json(filesPage(1, 2, 4, 2)));
        server.enqueue("/api/files.list", SlackApiMockServer.json(filesPage(2, 2, 4, 2)));

        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        paramMap.put("file_types", "pdfs");
        server.newClient(paramMap).getChannelFiles("C1", 2, f -> {});

        final List<Map<String, String>> requests = server.getRequests("/api/files.list");
        assertEquals(2, requests.size());
        assertEquals("pdfs", requests.get(0).get("types"));
        assertEquals("pdfs", requests.get(1).get("types"));
    }

    /**
     * A single page must not trigger a second request.
     */
    @Test
    public void test_singlePageMakesOneRequest() {
        server.enqueue("/api/files.list", SlackApiMockServer.json(filesPage(1, 1, 3, 3)));
        newClient().getChannelFiles("C1", 20, f -> {});
        assertEquals(1, server.getRequestCount("/api/files.list"));
    }

    /**
     * A response with no `paging` object at all must stop after the first page instead of
     * looping forever or throwing a NullPointerException. Files on any later page are silently
     * under-indexed -- {@code getChannelFiles} logs a warning for this case -- but the first
     * page's files must still reach the consumer.
     */
    @Test
    public void test_missingPagingStopsAfterFirstPage() {
        server.enqueue("/api/files.list", SlackApiMockServer.json("{\"ok\":true,\"files\":[{\"id\":\"F1_0\"}]}"));

        final List<File> collected = new ArrayList<>();
        newClient().getChannelFiles("C1", 20, collected::add);

        assertEquals(1, server.getRequestCount("/api/files.list"));
        assertEquals(1, collected.size());
    }

    private static String filesPage(final int page, final int pages, final int total, final int fileCount) {
        final StringBuilder buf = new StringBuilder("{\"ok\":true,\"files\":[");
        for (int i = 0; i < fileCount; i++) {
            if (i > 0) {
                buf.append(',');
            }
            buf.append("{\"id\":\"F").append(page).append('_').append(i).append("\"}");
        }
        buf.append("],\"paging\":{\"count\":")
                .append(fileCount)
                .append(",\"total\":")
                .append(total)
                .append(",\"page\":")
                .append(page)
                .append(",\"pages\":")
                .append(pages)
                .append("}}");
        return buf.toString();
    }

    private SlackClient newClient() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        return server.newClient(paramMap);
    }
}
