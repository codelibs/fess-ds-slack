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

    /** Whether the API request was successful. */
    protected Boolean ok;
    /** Error message if the request failed. */
    protected String error;
    /** Raw response body from the API. */
    protected String responseBody;

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

}
