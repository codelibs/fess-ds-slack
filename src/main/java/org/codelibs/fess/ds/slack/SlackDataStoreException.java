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

import org.codelibs.fess.exception.DataStoreException;

/**
 * Exception thrown when errors occur during Slack data store operations.
 * This includes API authentication failures, network issues, and data processing errors.
 */
public class SlackDataStoreException extends DataStoreException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public SlackDataStoreException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public SlackDataStoreException(final String message) {
        super(message);
    }

    /**
     * Creates a new exception with the specified cause.
     *
     * @param cause the cause of this exception
     */
    public SlackDataStoreException(final Throwable cause) {
        super(cause);
    }

}
