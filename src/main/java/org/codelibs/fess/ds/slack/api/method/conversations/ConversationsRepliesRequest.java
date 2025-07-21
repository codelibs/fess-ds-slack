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
 * Request class for the conversations.replies API method.
 * Retrieves thread replies for a specific message in a channel.
 */
public class ConversationsRepliesRequest extends Request<ConversationsRepliesResponse> {

    /** Channel ID containing the thread */
    protected final String channel;

    /** Timestamp of the parent message */
    protected final String ts;

    /** Pagination cursor for retrieving more results */
    protected String cursor;

    /** Latest timestamp to include in results */
    protected String latest;

    /** Oldest timestamp to include in results */
    protected String oldest;

    /** Maximum number of messages to return */
    protected Integer limit;

    /** Whether to include messages with timestamps matching boundaries */
    protected Boolean inclusive;

    /**
     * Constructs a new conversations.replies request.
     *
     * @param authentication the authentication credentials
     * @param channel the channel ID
     * @param ts the timestamp of the parent message
     */
    public ConversationsRepliesRequest(final Authentication authentication, final String channel, final String ts) {
        super(authentication);
        this.channel = channel;
        this.ts = ts;
    }

    /**
     * Executes the conversations.replies API request.
     *
     * @return the response containing thread replies
     */
    @Override
    public ConversationsRepliesResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsRepliesResponse.class);
    }

    /**
     * Sets the pagination cursor for retrieving more results.
     *
     * @param cursor the pagination cursor
     * @return this request instance for method chaining
     */
    public ConversationsRepliesRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets whether to include messages with timestamps matching boundaries.
     *
     * @param inclusive whether to include boundary timestamps
     * @return this request instance for method chaining
     */
    public ConversationsRepliesRequest inclusive(final Boolean inclusive) {
        this.inclusive = inclusive;
        return this;
    }

    /**
     * Sets the latest timestamp to include in results.
     *
     * @param latest the latest timestamp
     * @return this request instance for method chaining
     */
    public ConversationsRepliesRequest latest(final String latest) {
        this.latest = latest;
        return this;
    }

    /**
     * Sets the maximum number of messages to return.
     *
     * @param limit the maximum number of messages
     * @return this request instance for method chaining
     */
    public ConversationsRepliesRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets the oldest timestamp to include in results.
     *
     * @param oldest the oldest timestamp
     * @return this request instance for method chaining
     */
    public ConversationsRepliesRequest oldest(final String oldest) {
        this.oldest = oldest;
        return this;
    }

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "conversations.replies");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (ts != null) {
            request.param("ts", ts);
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
