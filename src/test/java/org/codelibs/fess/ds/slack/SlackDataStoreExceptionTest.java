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

import org.junit.jupiter.api.TestInfo;

import org.codelibs.fess.ds.slack.UnitDsTestCase;

/**
 * Test class for SlackDataStoreException.
 */
public class SlackDataStoreExceptionTest extends UnitDsTestCase {

    public void test_constructor_withMessage() {
        final String message = "Test error message";
        final SlackDataStoreException exception = new SlackDataStoreException(message);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    public void test_constructor_withMessageAndCause() {
        final String message = "Test error message";
        final Throwable cause = new RuntimeException("Cause exception");
        final SlackDataStoreException exception = new SlackDataStoreException(message, cause);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals(cause, exception.getCause());
        assertEquals("Cause exception", exception.getCause().getMessage());
    }

    public void test_constructor_withCause() {
        final Throwable cause = new IllegalArgumentException("Invalid argument");
        final SlackDataStoreException exception = new SlackDataStoreException(cause);

        assertNotNull(exception);
        assertNotNull(exception.getCause());
        assertEquals(cause, exception.getCause());
        assertEquals("Invalid argument", exception.getCause().getMessage());
    }

    public void test_exceptionCanBeThrown() {
        try {
            throw new SlackDataStoreException("Test exception");
        } catch (final SlackDataStoreException e) {
            assertEquals("Test exception", e.getMessage());
        }
    }

    public void test_exceptionWithNullMessage() {
        final SlackDataStoreException exception = new SlackDataStoreException((String) null);
        assertNotNull(exception);
        assertNull(exception.getMessage());
    }

    public void test_exceptionWithNullCause() {
        final SlackDataStoreException exception = new SlackDataStoreException((Throwable) null);
        assertNotNull(exception);
        assertNull(exception.getCause());
    }

    public void test_exceptionWithNullMessageAndCause() {
        final SlackDataStoreException exception = new SlackDataStoreException(null, null);
        assertNotNull(exception);
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    public void test_exceptionInheritance() {
        final SlackDataStoreException exception = new SlackDataStoreException("Test");
        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof RuntimeException);
    }

    public void test_exceptionStackTrace() {
        final SlackDataStoreException exception = new SlackDataStoreException("Test with stack trace");
        final StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull(stackTrace);
        assertTrue(stackTrace.length > 0);
    }

}
