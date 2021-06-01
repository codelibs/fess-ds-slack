/*
 * Copyright 2012-2021 CodeLibs Project and the Others.
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

public class UsersInfoRequest extends Request<UsersInfoResponse> {

    protected final String user;
    protected Boolean includeLocale;

    public UsersInfoRequest(final Authentication authentication, final String user) {
        super(authentication);
        this.user = user;
    }

    @Override
    public UsersInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), UsersInfoResponse.class);
    }

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
