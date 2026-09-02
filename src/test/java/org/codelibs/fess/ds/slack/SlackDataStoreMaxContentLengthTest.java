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

import org.apache.logging.log4j.Level;
import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers {@code SlackDataStore#getMaxContentLength}, the parser for {@code max_content_length}
 * (the extraction bound passed to Tika, distinct from {@code max_filesize}'s pre-download
 * transfer bound -- see that field's javadoc). Unset, blank, or non-numeric input must all fall
 * back to {@code DEFAULT_MAX_CONTENT_LENGTH} (-1), which tells the extractor to defer to {@code
 * ContentLengthHelper}'s per-MIME-type limit -- the same behavior this data store had before
 * this parameter existed, so an existing configuration that never mentions it keeps working
 * unchanged.
 */
public class SlackDataStoreMaxContentLengthTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    @Test
    public void test_unsetParameterDefersToContentLengthHelper() {
        final DataStoreParams paramMap = new DataStoreParams();
        assertEquals("unset must mean \"defer to ContentLengthHelper\" (-1), the pre-existing behavior", -1L,
                dataStore.getMaxContentLength(paramMap));
    }

    @Test
    public void test_blankParameterDefersToContentLengthHelper() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_content_length", "  ");
        assertEquals("a blank value must fall back the same way an unset one does", -1L, dataStore.getMaxContentLength(paramMap));
    }

    @Test
    public void test_configuredValueIsParsed() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_content_length", "5000");
        assertEquals(5000L, dataStore.getMaxContentLength(paramMap));
    }

    @Test
    public void test_nonNumericValueFallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_content_length", "not-a-number");
        assertEquals("an invalid value must not blow up the crawl -- fall back like the sibling max_filesize parser does", -1L,
                dataStore.getMaxContentLength(paramMap));
    }

    /**
     * A typo'd value must warn, matching every parameter this phase added -- before this, it
     * silently reverted to the default with no observable trace at all.
     */
    @Test
    public void test_nonNumericValue_logsWarning() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_content_length", "not-a-number");
        final TestLogAppender appender = TestLogAppender.attachTo(SlackDataStore.class);
        try {
            dataStore.getMaxContentLength(paramMap);
            assertTrue("a non-numeric max_content_length must warn", appender.hasEventAt(Level.WARN));
        } finally {
            appender.detach();
        }
    }
}
