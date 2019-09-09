/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
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
import org.codelibs.fess.ds.slack.api.Request;

public class ConversationsInfoRequest extends Request<ConversationsInfoResponse> {

    protected final String channel;
    protected Boolean includeLocale;

    public ConversationsInfoRequest(final String token, final String channel) {
        super(token);
        this.channel = channel;
    }

    @Override
    public ConversationsInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsInfoResponse.class);
    }

    public ConversationsInfoRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

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
