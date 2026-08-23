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

import java.util.Map;

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackDataStoreSecretsTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    /** Secret parameters must not be reachable from a crawl script. */
    @Test
    public void test_secretsAreNotExposedToScripts() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "xoxb-SECRET");
        paramMap.put("proxy_host", "proxy.internal");
        paramMap.put("proxy_port", "8080");
        paramMap.put("channels", "general");

        final Map<String, Object> resultMap = dataStore.newResultMap(paramMap);

        assertNull("token must not be exposed to scripts", resultMap.get("token"));
        assertNull("proxy_host must not be exposed to scripts", resultMap.get("proxy_host"));
        assertNull("proxy_port must not be exposed to scripts", resultMap.get("proxy_port"));
        assertEquals("general", resultMap.get("channels"));
    }
}
