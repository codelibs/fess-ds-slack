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

public class RequestTimeoutTest extends UnitDsTestCase {

    @Test
    public void test_defaultTimeouts() {
        final RequestContext context = new RequestContext("xoxb-test");
        assertEquals(20000, context.getConnectionTimeout());
        assertEquals(20000, context.getReadTimeout());
    }

    @Test
    public void test_setTimeouts() {
        final RequestContext context = new RequestContext("xoxb-test");
        context.setTimeouts(1000, 2000);
        assertEquals(1000, context.getConnectionTimeout());
        assertEquals(2000, context.getReadTimeout());
    }
}
