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
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.utflute.lastaflute.LastaFluteTestCase;

public class SlackDataStoreTest extends LastaFluteTestCase {

    private static Logger logger = LogManager.getLogger(SlackDataStoreTest.class);

    public SlackDataStore dataStore;

    @Override
    protected String prepareConfigFile() {
        return "test_app.xml";
    }

    @Override
    protected boolean isSuppressTestCaseTransaction() {
        return true;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        dataStore = new SlackDataStore();
    }

    @Override
    public void tearDown() throws Exception {
        ComponentUtil.setFessConfig(null);
        super.tearDown();
    }

    public void test_storeData() {
        // doStoreDataTest();
    }

    protected void doStoreDataTest() {

        final DataConfig dataConfig = new DataConfig();
        final IndexUpdateCallback callback = new IndexUpdateCallback() {
            @Override
            public void store(DataStoreParams paramMap, Map<String, Object> dataMap) {
                logger.info("[{}.{}] dataMap = {}", getClass(), getName(), dataMap);
            }

            @Override
            public long getExecuteTime() {
                return 0;
            }

            @Override
            public long getDocumentSize() {
                return 0;
            }

            @Override
            public void commit() {
            }
        };
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "");
        paramMap.put("channels", "");
        final Map<String, String> scriptMap = new HashMap<>();
        final Map<String, Object> defaultDataMap = new HashMap<>();

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        scriptMap.put(fessConfig.getIndexFieldTitle(), "message.user + \" #\" + message.channel");
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text + \"\\n\" + message.attachments");
        scriptMap.put(fessConfig.getIndexFieldCreated(), "message.timestamp");
        scriptMap.put(fessConfig.getIndexFieldUrl(), "message.permalink");

        dataStore.storeData(dataConfig, callback, paramMap, scriptMap, defaultDataMap);

    }

    // Test getMaxFilesize method
    public void test_getMaxFilesize_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    public void test_getMaxFilesize_validValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "5000000");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(5000000L, maxFilesize);
    }

    public void test_getMaxFilesize_invalidValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "invalid");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    // Test isIgnoreError method
    public void test_isIgnoreError_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    public void test_isIgnoreError_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "true");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    public void test_isIgnoreError_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "false");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertFalse(ignoreError);
    }

    // Test isFileCrawl method
    public void test_isFileCrawl_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    public void test_isFileCrawl_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "true");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertTrue(fileCrawl);
    }

    public void test_isFileCrawl_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "false");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    // Test getName method
    public void test_getName() {
        final String name = dataStore.getName();
        assertEquals("SlackDataStore", name);
    }

    // Test setExtractorName method
    public void test_setExtractorName() {
        dataStore.setExtractorName("customExtractor");
        // No direct getter to verify, but we can ensure no exception is thrown
        assertNotNull(dataStore);
    }

    // Test thread pool creation
    public void test_newFixedThreadPool() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(2);
        assertNotNull(executorService);
        executorService.shutdown();
    }

    // Test newFixedThreadPool with different thread counts
    public void test_newFixedThreadPool_singleThread() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(1);
        assertNotNull(executorService);
        executorService.shutdown();
    }

    public void test_newFixedThreadPool_multipleThreads() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(5);
        assertNotNull(executorService);
        executorService.shutdown();
    }

}
