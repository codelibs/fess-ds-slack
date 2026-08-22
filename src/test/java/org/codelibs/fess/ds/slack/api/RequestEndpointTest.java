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

import org.codelibs.fess.ds.slack.UnitDsTestCase;
import org.junit.jupiter.api.Test;

/**
 * Mutates {@link Request}'s process-wide static endpoint field, the same global
 * {@link org.codelibs.fess.ds.slack.SlackApiMockServer} points at {@code Request.setEndpoint}
 * during {@code start()}/{@code stop()}. Safe today only because surefire runs every test class
 * in this module sequentially in one JVM; running test classes in parallel would let this race
 * with any test using the mock server.
 */
public class RequestEndpointTest extends UnitDsTestCase {

    @Test
    public void test_defaultEndpoint() {
        Request.resetEndpoint();
        assertEquals("https://slack.com/api/", Request.getEndpoint());
    }

    @Test
    public void test_setEndpoint() {
        try {
            Request.setEndpoint("http://localhost:9999/api/");
            assertEquals("http://localhost:9999/api/", Request.getEndpoint());
        } finally {
            Request.resetEndpoint();
        }
    }

    @Test
    public void test_resetEndpoint() {
        Request.setEndpoint("http://localhost:9999/api/");
        Request.resetEndpoint();
        assertEquals("https://slack.com/api/", Request.getEndpoint());
    }
}
