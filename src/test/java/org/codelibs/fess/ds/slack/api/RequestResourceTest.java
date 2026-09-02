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
package org.codelibs.fess.ds.slack.api;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.codelibs.fess.ds.slack.SlackApiMockServer;
import org.codelibs.fess.ds.slack.SlackDataStoreException;
import org.codelibs.fess.ds.slack.UnitDsTestCase;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoRequest;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class RequestResourceTest extends UnitDsTestCase {

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
     * {@code ObjectMapper.readValue((String) null, ...)} throws {@link IllegalArgumentException},
     * not {@link java.io.IOException}, so a catch narrowed to {@code IOException} would let this
     * escape unwrapped instead of producing the {@link SlackDataStoreException} every other parse
     * failure produces.
     */
    @Test
    public void test_parseResponseWithNullContentThrowsSlackDataStoreException() {
        Assertions.assertThrows(SlackDataStoreException.class, () -> new TeamInfoRequest(null).parseResponse(null, TeamInfoResponse.class));
    }

    /**
     * curl4j (confirmed by disassembling curl4j-1.3.3's
     * org.codelibs.curl.io.ContentOutputStream: PREFIX = "curl4j-", SUFFIX =
     * ".tmp", directory = Curl.tmpDir, i.e. java.io.tmpdir) spills a response
     * larger than its threshold to a temp file that is only removed once the
     * CurlResponse -- and, through it, the ContentCache holding the file
     * handle -- is closed. Confirm none survive the call.
     */
    @Test
    public void test_largeResponseLeavesNoTempFile() throws Exception {
        server.enqueue("/api/users.list", SlackApiMockServer.json(largeUsersList()));

        final long before = countCurlTempFiles();
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-test");
        new org.codelibs.fess.ds.slack.SlackClient(paramMap);
        final long after = countCurlTempFiles();

        assertEquals("a users.list response over curl4j's spill threshold must not leave a temp file behind", before, after);
    }

    private static long countCurlTempFiles() throws Exception {
        final Path tmp = new File(System.getProperty("java.io.tmpdir")).toPath();
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("curl4j-")).count();
        }
    }

    private static String largeUsersList() {
        final StringBuilder buf = new StringBuilder("{\"ok\":true,\"members\":[");
        for (int i = 0; i < 20000; i++) {
            if (i > 0) {
                buf.append(',');
            }
            buf.append("{\"id\":\"U")
                    .append(i)
                    .append("\",\"name\":\"user")
                    .append(i)
                    .append("\",\"profile\":{\"display_name\":\"Display Name ")
                    .append(i)
                    .append("\"}}");
        }
        return buf.append("],\"response_metadata\":{\"next_cursor\":\"\"}}").toString();
    }
}
