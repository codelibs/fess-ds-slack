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

public class ConversationsListRequest extends Request<ConversationsListResponse> {

    protected String cursor, types;
    protected Boolean excludeArchived;
    protected Integer limit;

    public ConversationsListRequest(final Authentication authentication) {
        super(authentication);
    }

    @Override
    public ConversationsListResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsListResponse.class);
    }

    public ConversationsListRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    public ConversationsListRequest excludeArchived(final Boolean excludeArchived) {
        this.excludeArchived = excludeArchived;
        return this;
    }

    public ConversationsListRequest types(final String types) {
        this.types = types;
        return this;
    }

    public ConversationsListRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

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
