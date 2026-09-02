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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Abstract base class for all Slack API responses providing common response handling.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract class Response {

    /**
     * Default constructor.
     */
    public Response() {
    }

    /**
     * Whether the API request was successful.
     *
     * <p>
     * {@code @JsonProperty("ok")} is explicit, not incidental: this field's binding currently
     * survives only because {@link #getOk()} -- a real, {@code get}-prefixed JavaBean getter --
     * happens to exist beside the bare convenience method {@link #ok()}, which carries neither
     * an {@code is} nor a {@code get} prefix and so is invisible to Jackson's bean introspector
     * on its own (see {@link org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesResponse#hasMore}
     * for what happens when no such sibling exists). The binding must not depend on {@link
     * #getOk()} continuing to exist, so it is pinned here directly.
     * </p>
     */
    @JsonProperty("ok")
    protected Boolean ok;
    /** Error message if the request failed. */
    protected String error;
    /** Raw response body from the API. */
    protected String responseBody;
    /**
     * Whether this response was returned only because {@code Request.execute} exhausted its
     * retries against a persistently retryable (429/5xx) HTTP status, as opposed to Slack
     * answering with this body on the first attempt. Never populated from JSON -- there is no
     * such field in a Slack API response -- only ever set by {@code Request.execute} itself, so
     * {@code SlackClient.handleApiError} can distinguish "we gave up after N attempts" from
     * "Slack said no", a distinction the parsed {@code ok}/{@code error} body alone cannot make.
     */
    protected boolean retriesExhausted;

    /**
     * Returns whether the API request was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean ok() {
        return ok == null ? false : ok;
    }

    /**
     * Returns the success status of the API request.
     *
     * @return the success status Boolean
     */
    public Boolean getOk() {
        return ok;
    }

    /**
     * Returns the error message if the request failed.
     *
     * @return the error message, or null if no error
     */
    public String getError() {
        return error;
    }

    /**
     * Returns the raw response body from the API.
     *
     * @return the response body
     */
    public String responseBody() {
        return responseBody;
    }

    /**
     * Sets the raw response body and returns this response instance.
     *
     * @param responseBody the response body to set
     * @param <T> the specific response type
     * @return this response instance for method chaining
     */
    public <T extends Response> T responseBody(final String responseBody) {
        this.responseBody = responseBody;
        return (T) this;
    }

    /**
     * Returns whether this response was returned only because retries were exhausted. See
     * {@link #retriesExhausted} for what this does and does not mean.
     *
     * @return true if {@code Request.execute} gave up retrying rather than Slack answering ok
     */
    public boolean retriesExhausted() {
        return retriesExhausted;
    }

    /**
     * Sets whether this response was returned only because retries were exhausted, and returns
     * this response instance. Mirrors {@link #responseBody(String)}'s chaining shape.
     *
     * @param retriesExhausted the value to set
     * @param <T> the specific response type
     * @return this response instance for method chaining
     */
    public <T extends Response> T retriesExhausted(final boolean retriesExhausted) {
        this.retriesExhausted = retriesExhausted;
        return (T) this;
    }

}
