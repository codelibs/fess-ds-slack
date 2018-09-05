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
package org.codelibs.fess.ds.slack.api.method.chat;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.ds.slack.api.SlackClient;

public class ChatGetPermalinkRequest extends Request<ChatGetPermalinkResponse> {

    protected final String channel, ts;

    public ChatGetPermalinkRequest(final SlackClient client, final String channel, final String ts) {
        super(client);
        this.channel = channel;
        this.ts = ts;
    }

    @Override
    public ChatGetPermalinkResponse execute() {
        return parseResponse(request().execute().getContentAsString(), ChatGetPermalinkResponse.class);
    }

    private CurlRequest request() {
        final CurlRequest request = client.request(GET, "chat.getPermalink");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (ts != null) {
            request.param("message_ts", ts);
        }
        return request;
    }

}