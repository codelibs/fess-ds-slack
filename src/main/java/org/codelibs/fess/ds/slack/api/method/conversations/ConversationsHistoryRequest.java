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
package org.codelibs.fess.ds.slack.api.method.conversations;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.Request;

/**
 * Request class for retrieving conversation history from a Slack channel.
 * Implements the conversations.history Slack Web API method to fetch messages
 * from a specific channel with optional filtering and pagination parameters.
 */
public class ConversationsHistoryRequest extends Request<ConversationsHistoryResponse> {

    /** The channel ID to retrieve history from */
    protected final String channel;
    /** Pagination cursor for fetching next page of results */
    protected String cursor;
    /** End of time range of messages to include in results (Unix timestamp) */
    protected String latest;
    /** Start of time range of messages to include in results (Unix timestamp) */
    protected String oldest;
    /** Maximum number of messages to return (default: 100, max: 1000) */
    protected Integer limit;
    /** Include messages with latest or oldest timestamp in results */
    protected Boolean inclusive;

    /**
     * Constructs a new conversations history request.
     *
     * @param authentication the authentication credentials for API access
     * @param channel the channel ID to retrieve history from
     */
    public ConversationsHistoryRequest(final Authentication authentication, final String channel) {
        super(authentication);
        this.channel = channel;
    }

    /**
     * Executes the conversations.history API request.
     *
     * @return the response containing message history and pagination metadata
     */
    @Override
    public ConversationsHistoryResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsHistoryResponse.class);
    }

    /**
     * Sets the pagination cursor for retrieving the next page of results.
     *
     * @param cursor the pagination cursor from a previous response
     * @return this request instance for method chaining
     */
    public ConversationsHistoryRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets whether to include messages with latest or oldest timestamp in results.
     *
     * @param inclusive true to include boundary timestamps, false to exclude them
     * @return this request instance for method chaining
     */
    public ConversationsHistoryRequest inclusive(final Boolean inclusive) {
        this.inclusive = inclusive;
        return this;
    }

    /**
     * Sets the end of time range of messages to include (Unix timestamp).
     *
     * @param latest the latest timestamp to include in results
     * @return this request instance for method chaining
     */
    public ConversationsHistoryRequest latest(final String latest) {
        this.latest = latest;
        return this;
    }

    /**
     * Sets the maximum number of messages to return per page.
     *
     * @param limit maximum number of messages (1-1000, default: 100)
     * @return this request instance for method chaining
     */
    public ConversationsHistoryRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets the start of time range of messages to include (Unix timestamp).
     *
     * @param oldest the oldest timestamp to include in results
     * @return this request instance for method chaining
     */
    public ConversationsHistoryRequest oldest(final String oldest) {
        this.oldest = oldest;
        return this;
    }

    /**
     * Builds the HTTP request with all configured parameters.
     *
     * @return the configured HTTP request
     */
    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "conversations.history");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (inclusive != null) {
            request.param("inclusive", inclusive.toString());
        }
        if (latest != null) {
            request.param("latest", latest);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        if (oldest != null) {
            request.param("oldest", oldest);
        }
        return request;
    }

}
