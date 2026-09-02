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

import org.codelibs.fess.ds.slack.SlackDataStoreException;

/**
 * Thrown when a Slack Web API response carries an {@code ok: false} error code that means the
 * crawl cannot proceed at all -- most commonly that the configured token can no longer
 * authenticate ({@code invalid_auth}, {@code token_revoked}, {@code account_inactive},
 * {@code missing_scope}, {@code not_authed}, {@code token_expired}).
 *
 * <p>
 * This is deliberately different from a per-channel or per-message failure, which is recorded
 * through {@code FailureUrlService} and does not stop the crawl: a fatal error here means every
 * later call would fail the same way, so this exception is meant to propagate uncaught out of
 * {@code storeData} and fail the whole job, rather than let it finish and report a false
 * "success" with zero or partial documents.
 * </p>
 */
public class SlackApiException extends SlackDataStoreException {

    private static final long serialVersionUID = 1L;

    /** The Slack Web API method that returned the error, e.g. {@code "users.list"}. */
    private final String method;

    /** The Slack error code, e.g. {@code "invalid_auth"}. */
    private final String errorCode;

    /**
     * Creates a new exception for a fatal Slack API error.
     *
     * <p>
     * The message intentionally carries only the method name and the error code, not the raw
     * response body: the body may repeat request parameters or other detail that does not
     * belong in a warning or exception message surfaced to an administrator.
     * </p>
     *
     * @param method the Slack Web API method name that returned the error
     * @param errorCode the Slack error code
     */
    public SlackApiException(final String method, final String errorCode) {
        super("Slack API \"" + method + "\" returned a fatal error: \"" + errorCode + "\". The crawl cannot continue.");
        this.method = method;
        this.errorCode = errorCode;
    }

    /**
     * Returns the Slack Web API method that returned the error.
     *
     * @return the method name, e.g. {@code "users.list"}
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the Slack error code that triggered this exception.
     *
     * @return the error code, e.g. {@code "invalid_auth"}
     */
    public String getErrorCode() {
        return errorCode;
    }

}
