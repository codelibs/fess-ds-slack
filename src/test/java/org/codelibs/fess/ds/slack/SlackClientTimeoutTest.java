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

import java.net.InetSocketAddress;

import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Confirms {@code read_timeout} actually aborts a stalled socket rather than
 * merely being stored. A local {@link HttpServer} accepts the connection but
 * never writes a response, so the only thing that can unblock the caller is
 * curl4j applying the configured read timeout to the underlying
 * {@code HttpURLConnection}.
 *
 * <p>
 * This test is time-dependent: it holds the server open for 2 seconds --
 * more than six times the configured 300 ms read timeout -- and asserts the
 * client gives up in well under that (observed: ~300 ms). The margin is wide
 * enough that it should not be flaky, but if it ever proves otherwise, widen
 * it rather than deleting or weakening the assertion.
 * </p>
 *
 * <p>
 * The hold is intentionally short rather than the many-second sleep a naive
 * version of this test might use: {@link HttpServer#stop(int)} blocks in
 * {@code finally} until the in-flight handler thread returns, so the sleep
 * duration is a floor on this test's wall-clock cost even though the
 * assertion itself resolves almost immediately.
 * </p>
 */
public class SlackClientTimeoutTest extends UnitDsTestCase {

    @Test
    public void test_readTimeoutAborts() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            // Never write a response; hold the connection open long enough for
            // the read timeout to fire well before this sleep completes.
            try {
                Thread.sleep(2000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            Request.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
            final DataStoreParams paramMap = new DataStoreParams();
            paramMap.put("token", "xoxb-test");
            paramMap.put("read_timeout", "300");
            final long started = System.currentTimeMillis();
            try {
                new SlackClient(paramMap);
                fail("expected a timeout");
            } catch (final Exception expected) {
                final long elapsed = System.currentTimeMillis() - started;
                assertTrue("should abort well before the 2s server hold, took " + elapsed + "ms", elapsed < 1500L);
            }
        } finally {
            Request.resetEndpoint();
            server.stop(0);
        }
    }
}
