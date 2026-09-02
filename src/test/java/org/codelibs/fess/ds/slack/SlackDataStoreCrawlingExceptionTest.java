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

import java.util.concurrent.atomic.AtomicBoolean;

import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Covers {@code SlackDataStore.resolveFailureUrl}, the helper shared by both
 * {@code catch (CrawlingAccessException)} blocks in {@code processMessage} and
 * {@code processFile} (C22): a {@link DataStoreCrawlingException} carries its own, more precise
 * URL and an abort flag that earlier code ignored, silently recording the wrong URL and never
 * stopping a crawl that asked to be stopped.
 *
 * <p>
 * Every test here also asserts the inherited {@code alive} flag is left alone. That is the
 * load-bearing half: a data store is a LastaDi singleton reused by every crawl for the lifetime
 * of the JVM, and nothing in Fess ever sets {@code alive} back to {@code true}, so aborting a
 * crawl through {@code stop()} would leave every later crawl of every Slack {@code DataConfig}
 * walking zero channels and reporting success until Fess restarts.
 * </p>
 */
public class SlackDataStoreCrawlingExceptionTest extends UnitDsTestCase {

    private TestableSlackDataStore dataStore;

    private AtomicBoolean crawlAlive;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new TestableSlackDataStore();
        crawlAlive = new AtomicBoolean(true);
    }

    @Test
    public void test_dataStoreCrawlingException_usesItsOwnUrl_notTheFallback() {
        final DataStoreCrawlingException dce =
                new DataStoreCrawlingException("https://example.com/real-resource", "boom", new RuntimeException("cause"));
        final String resolved = dataStore.resolveFailureUrl(dce, "https://example.com/fallback-permalink", crawlAlive);
        assertEquals("https://example.com/real-resource", resolved);
    }

    @Test
    public void test_dataStoreCrawlingException_aborted_clearsTheCrawlFlagOnly() {
        assertTrue("the crawl flag starts set", crawlAlive.get());
        final DataStoreCrawlingException dce =
                new DataStoreCrawlingException("https://example.com/x", "boom", new RuntimeException(), true);
        dataStore.resolveFailureUrl(dce, "fallback", crawlAlive);
        assertFalse("an aborting exception clears this crawl's flag", crawlAlive.get());
        assertTrue("the shared, never-reset alive flag must not be touched", dataStore.isAlive());
    }

    @Test
    public void test_dataStoreCrawlingException_notAborted_leavesTheCrawlFlagSet() {
        final DataStoreCrawlingException dce =
                new DataStoreCrawlingException("https://example.com/x", "boom", new RuntimeException(), false);
        dataStore.resolveFailureUrl(dce, "fallback", crawlAlive);
        assertTrue("the crawl flag stays set when the exception does not ask to abort", crawlAlive.get());
        assertTrue("the shared alive flag stays set", dataStore.isAlive());
    }

    @Test
    public void test_plainCrawlingAccessException_usesFallbackUrl_andLeavesBothFlagsSet() {
        final CrawlingAccessException other = new CrawlingAccessException("boom");
        final String resolved = dataStore.resolveFailureUrl(other, "fallback-url", crawlAlive);
        assertEquals("fallback-url", resolved);
        assertTrue("the crawl flag stays set for a plain CrawlingAccessException", crawlAlive.get());
        assertTrue("the shared alive flag stays set", dataStore.isAlive());
    }

    /** Exposes the inherited, protected {@code alive} flag ({@code AbstractDataStore}) for assertions. */
    private static final class TestableSlackDataStore extends SlackDataStore {
        boolean isAlive() {
            return alive;
        }
    }
}
