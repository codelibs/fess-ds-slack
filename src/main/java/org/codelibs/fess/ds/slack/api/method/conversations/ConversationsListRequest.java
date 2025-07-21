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
 * Request class for listing Slack channels.
 * Implements the conversations.list Slack Web API method to retrieve
 * all channels that the authenticated user has access to, with optional
 * filtering and pagination parameters.
 */
public class ConversationsListRequest extends Request<ConversationsListResponse> {

    /** Pagination cursor for fetching next page of results */
    protected String cursor;
    /** Comma-separated list of channel types to include (e.g., "public_channel,private_channel") */
    protected String types;
    /** Whether to exclude archived channels from results */
    protected Boolean excludeArchived;
    /** Maximum number of channels to return (default: 100, max: 1000) */
    protected Integer limit;

    /**
     * Constructs a new conversations list request.
     *
     * @param authentication the authentication credentials for API access
     */
    public ConversationsListRequest(final Authentication authentication) {
        super(authentication);
    }

    /**
     * Executes the conversations.list API request.
     *
     * @return the response containing list of channels and pagination metadata
     */
    @Override
    public ConversationsListResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsListResponse.class);
    }

    /**
     * Sets the pagination cursor for retrieving the next page of results.
     *
     * @param cursor the pagination cursor from a previous response
     * @return this request instance for method chaining
     */
    public ConversationsListRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets whether to exclude archived channels from the results.
     *
     * @param excludeArchived true to exclude archived channels, false to include them
     * @return this request instance for method chaining
     */
    public ConversationsListRequest excludeArchived(final Boolean excludeArchived) {
        this.excludeArchived = excludeArchived;
        return this;
    }

    /**
     * Sets the types of channels to include in the results.
     *
     * @param types comma-separated list of channel types (e.g., "public_channel,private_channel")
     * @return this request instance for method chaining
     */
    public ConversationsListRequest types(final String types) {
        this.types = types;
        return this;
    }

    /**
     * Sets the maximum number of channels to return per page.
     *
     * @param limit maximum number of channels (1-1000, default: 100)
     * @return this request instance for method chaining
     */
    public ConversationsListRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Builds the HTTP request with all configured parameters.
     *
     * @return the configured HTTP request
     */
    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "conversations.list");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (excludeArchived != null) {
            request.param("exclude_archived", excludeArchived.toString());
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        if (types != null) {
            request.param("types", types);
        }
        return request;
    }

}
