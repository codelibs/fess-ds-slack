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
package org.codelibs.fess.ds.slack.api.method.users;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.Request;

/**
 * Request to retrieve a list of users in the Slack workspace.
 * Supports pagination and various filtering options.
 */
public class UsersListRequest extends Request<UsersListResponse> {

    /** Pagination cursor for retrieving the next page of results. */
    protected String cursor;
    /** Whether to include locale information in the response. */
    protected Boolean includeLocale;
    /** Whether to include presence information for users. */
    protected Boolean presence;
    /** Maximum number of users to return per page. */
    protected Integer limit;

    /**
     * Creates a new users.list request with the specified authentication.
     *
     * @param authentication the authentication credentials
     */
    public UsersListRequest(final Authentication authentication) {
        super(authentication);
    }

    @Override
    public UsersListResponse execute() {
        return parseResponse(request().execute().getContentAsString(), UsersListResponse.class);
    }

    /**
     * Sets the pagination cursor for retrieving the next page of results.
     *
     * @param cursor the pagination cursor
     * @return this request instance for method chaining
     */
    public UsersListRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets whether to include locale information in the response.
     *
     * @param includeLocale whether to include locale information
     * @return this request instance for method chaining
     */
    public UsersListRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

    /**
     * Sets the maximum number of users to return per page.
     *
     * @param limit the maximum number of users per page
     * @return this request instance for method chaining
     */
    public UsersListRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets whether to include presence information for users.
     *
     * @param presence whether to include presence information
     * @return this request instance for method chaining
     */
    public UsersListRequest presence(final Boolean presence) {
        this.presence = presence;
        return this;
    }

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "users.list");
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (includeLocale != null) {
            request.param("include_locale", includeLocale.toString());
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        if (presence != null) {
            request.param("presence", presence.toString());
        }
        return request;
    }

}
