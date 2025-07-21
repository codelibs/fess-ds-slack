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
 * Request class for retrieving information about a Slack channel.
 * Implements the conversations.info Slack Web API method to get
 * detailed channel information including name, purpose, and settings.
 */
public class ConversationsInfoRequest extends Request<ConversationsInfoResponse> {

    /** The channel ID to retrieve information for */
    protected final String channel;
    /** Whether to include locale information in the response */
    protected Boolean includeLocale;

    /**
     * Constructs a new conversations info request.
     *
     * @param authentication the authentication credentials for API access
     * @param channel the channel ID to retrieve information for
     */
    public ConversationsInfoRequest(final Authentication authentication, final String channel) {
        super(authentication);
        this.channel = channel;
    }

    /**
     * Executes the conversations.info API request.
     *
     * @return the response containing detailed channel information
     */
    @Override
    public ConversationsInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsInfoResponse.class);
    }

    /**
     * Sets whether to include locale information in the response.
     *
     * @param includeLocale true to include locale data, false to exclude it
     * @return this request instance for method chaining
     */
    public ConversationsInfoRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

    /**
     * Builds the HTTP request with all configured parameters.
     *
     * @return the configured HTTP request
     */
    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "conversations.info");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (includeLocale != null) {
            request.param("include_locale", includeLocale.toString());
        }
        return request;
    }

}
