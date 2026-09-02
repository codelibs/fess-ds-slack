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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.util.ComponentUtil;
import org.codelibs.fess.ds.slack.UnitDsTestCase;

public class SlackDataStoreTest extends UnitDsTestCase {

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
    public void setUp(TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    @Override
    public void tearDown(TestInfo testInfo) throws Exception {
        ComponentUtil.setFessConfig(null);
        super.tearDown(testInfo);
    }

    // Test getMaxFilesize method
    @Test
    public void test_getMaxFilesize_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    @Test
    public void test_getMaxFilesize_validValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "5000000");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(5000000L, maxFilesize);
    }

    @Test
    public void test_getMaxFilesize_invalidValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "invalid");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    // Test isIgnoreError method
    @Test
    public void test_isIgnoreError_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    @Test
    public void test_isIgnoreError_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "true");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    @Test
    public void test_isIgnoreError_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "false");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertFalse(ignoreError);
    }

    // Test isFileCrawl method
    @Test
    public void test_isFileCrawl_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    @Test
    public void test_isFileCrawl_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "true");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertTrue(fileCrawl);
    }

    @Test
    public void test_isFileCrawl_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "false");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    // Test getSupportedMimeTypes method
    @Test
    public void test_getSupportedMimeTypes_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final java.util.List<String> mimeTypes = dataStore.getSupportedMimeTypes(paramMap);
        assertEquals(1, mimeTypes.size());
        assertEquals(".*", mimeTypes.get(0));
    }

    @Test
    public void test_getSupportedMimeTypes_blankFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("supported_mimetypes", "");
        final java.util.List<String> mimeTypes = dataStore.getSupportedMimeTypes(paramMap);
        assertEquals(1, mimeTypes.size());
        assertEquals(".*", mimeTypes.get(0));
    }

    @Test
    public void test_getSupportedMimeTypes_explicitValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("supported_mimetypes", "application/pdf,text/plain");
        final java.util.List<String> mimeTypes = dataStore.getSupportedMimeTypes(paramMap);
        assertEquals(2, mimeTypes.size());
        assertEquals("application/pdf", mimeTypes.get(0));
        assertEquals("text/plain", mimeTypes.get(1));
    }

    // Test getName method
    @Test
    public void test_getName() {
        final String name = dataStore.getName();
        assertEquals("SlackDataStore", name);
    }

    // Test setExtractorName method
    @Test
    public void test_setExtractorName() {
        assertEquals("tikaExtractor", dataStore.extractorName);
        dataStore.setExtractorName("customExtractor");
        assertEquals("customExtractor", dataStore.extractorName);
    }

    // Test thread pool creation
    @Test
    public void test_newFixedThreadPool() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(2);
        assertNotNull(executorService);
        executorService.shutdown();
    }

    // Test newFixedThreadPool with different thread counts
    @Test
    public void test_newFixedThreadPool_singleThread() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(1);
        assertNotNull(executorService);
        executorService.shutdown();
    }

    @Test
    public void test_newFixedThreadPool_multipleThreads() {
        final java.util.concurrent.ExecutorService executorService = dataStore.newFixedThreadPool(5);
        assertNotNull(executorService);
        executorService.shutdown();
    }

}
