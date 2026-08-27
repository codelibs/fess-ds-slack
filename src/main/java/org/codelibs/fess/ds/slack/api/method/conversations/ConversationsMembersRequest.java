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
import org.codelibs.fess.ds.slack.api.RequestContext;
import org.codelibs.fess.ds.slack.api.Request;

/**
 * Request class for listing the member user IDs of a Slack channel.
 * Implements the conversations.members Slack Web API method, which is the only Slack Web
 * API method that actually returns a channel's membership: neither conversations.list nor
 * conversations.info populates {@link org.codelibs.fess.ds.slack.api.type.Channel#getMembers()}.
 */
public class ConversationsMembersRequest extends Request<ConversationsMembersResponse> {

    /** The channel ID to list members for. */
    protected final String channel;
    /** Pagination cursor for fetching the next page of results. */
    protected String cursor;
    /** Maximum number of members to return per page. */
    protected Integer limit;

    /**
     * Constructs a new conversations members request.
     *
     * @param requestContext the request context for API access
     * @param channel the channel ID to list members for
     */
    public ConversationsMembersRequest(final RequestContext requestContext, final String channel) {
        super(requestContext);
        this.channel = channel;
    }

    /**
     * Executes the conversations.members API request.
     *
     * @return the response containing the channel's member user IDs and pagination metadata
     */
    @Override
    public ConversationsMembersResponse execute() {
        return execute(request(), ConversationsMembersResponse.class);
    }

    /**
     * Sets the pagination cursor for retrieving the next page of results.
     *
     * @param cursor the pagination cursor from a previous response
     * @return this request instance for method chaining
     */
    public ConversationsMembersRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets the maximum number of members to return per page.
     *
     * @param limit maximum number of members
     * @return this request instance for method chaining
     */
    public ConversationsMembersRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Builds the HTTP request with all configured parameters.
     *
     * @return the configured HTTP request
     */
    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "conversations.members");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        return request;
    }

}
