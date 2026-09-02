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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.codelibs.curl.CurlResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.sun.net.httpserver.HttpServer;

public class SlackClientFileProxyTest extends UnitDsTestCase {

    private SlackApiMockServer server;

    private HttpServer fakeProxy;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new SlackApiMockServer();
        server.start();
    }

    @Override
    public void tearDown(final TestInfo testInfo) throws Exception {
        if (fakeProxy != null) {
            fakeProxy.stop(0);
        }
        server.stop();
        super.tearDown(testInfo);
    }

    /**
     * {@code getFileResponse} must honour {@code authentication.getHttpProxy()}
     * the same way {@link org.codelibs.fess.ds.slack.api.Request#getCurlRequest}
     * does.
     *
     * <p>
     * Verified end-to-end rather than by inspecting the request object: a
     * local {@link HttpServer} stands in for an HTTP proxy (confirmed
     * experimentally -- {@code java.net.URL#openConnection(Proxy)} sends the
     * absolute-form request line and {@code Host} header a real proxy would
     * see straight to it). The file URL uses the {@code .invalid} TLD, which
     * RFC 2606 reserves as guaranteed unresolvable, so this call can only
     * succeed by routing through the configured proxy -- a direct connection
     * attempt would fail with an unknown-host error before ever reaching the
     * stand-in server.
     * </p>
     */
    @Test
    public void test_getFileResponseHonoursConfiguredProxy() throws Exception {
        final AtomicReference<String> receivedUri = new AtomicReference<>();
        fakeProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeProxy.createContext("/", exchange -> {
            receivedUri.set(exchange.getRequestURI().toString());
            final byte[] body = "file-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fakeProxy.setExecutor(null);
        fakeProxy.start();

        final SlackClient client = server.newClient("xoxb-test");
        client.authentication.setHttpProxy("127.0.0.1", fakeProxy.getAddress().getPort());

        try (CurlResponse response = client.getFileResponse("http://slack-files.invalid/f123")) {
            assertEquals("file-content", response.getContentAsString());
        }

        assertNotNull("the configured proxy must have received the file download request", receivedUri.get());
        assertTrue("the request reaching the proxy must target the original file URL", receivedUri.get().contains("slack-files.invalid"));
    }
}
