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
 * Request to retrieve information about a specific user in Slack.
 */
public class UsersInfoRequest extends Request<UsersInfoResponse> {

    /** The user ID to retrieve information for. */
    protected final String user;
    /** Whether to include locale information in the response. */
    protected Boolean includeLocale;

    /**
     * Creates a new users.info request with the specified authentication and user.
     *
     * @param authentication the authentication credentials
     * @param user the user ID to get information for
     */
    public UsersInfoRequest(final Authentication authentication, final String user) {
        super(authentication);
        this.user = user;
    }

    @Override
    public UsersInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), UsersInfoResponse.class);
    }

    /**
     * Sets whether to include locale information in the response.
     *
     * @param includeLocale whether to include locale information
     * @return this request instance for method chaining
     */
    public UsersInfoRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "users.info");
        if (user != null) {
            request.param("user", user);
        }
        if (includeLocale != null) {
            request.param("include_locale", includeLocale.toString());
        }
        return request;
    }

}
