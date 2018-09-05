/*
 * Copyright 2012-2018 CodeLibs Project and the Others.
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
import org.codelibs.fess.ds.slack.api.SlackClient;

public class ConversationsRepliesRequest extends Request<ConversationsRepliesResponse> {

    protected final String channel, ts;
    protected String cursor, latest, oldest;
    protected Integer limit;
    protected Boolean inclusive;

    public ConversationsRepliesRequest(final SlackClient client, final String channel, final String ts) {
        super(client);
        this.channel = channel;
        this.ts = ts;
    }

    @Override
    public ConversationsRepliesResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ConversationsRepliesResponse.class);
    }

    public ConversationsRepliesRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    public ConversationsRepliesRequest inclusive(final Boolean inclusive) {
        this.inclusive = inclusive;
        return this;
    }

    public ConversationsRepliesRequest latest(final String latest) {
        this.latest = latest;
        return this;
    }

    public ConversationsRepliesRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    public ConversationsRepliesRequest oldest(final String oldest) {
        this.oldest = oldest;
        return this;
    }

    private CurlRequest request() {
        final CurlRequest request = client.request(GET, "conversations.replies");
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