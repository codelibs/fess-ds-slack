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

import java.io.IOException;
import java.util.Scanner;

import org.codelibs.fess.ds.slack.SlackDataStoreException;
import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.ds.slack.api.SlackClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

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
        final StringBuilder result = new StringBuilder();
        final GenericUrl url = buildUrl(client.endpoint(), channel, ts, cursor, inclusive, latest, limit, oldest);
        try {
            final HttpRequest request = client.request().buildGetRequest(url);
            final HttpResponse response = request.execute();
            @SuppressWarnings("resource")
            final Scanner s = new Scanner(response.getContent()).useDelimiter("\\A");
            result.append(s.hasNext() ? s.next() : "");
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to request: " + url, e);
        }

        final String json = result.toString();
        final ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, ConversationsRepliesResponse.class);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + json + "\"", e);
        }
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

    protected GenericUrl buildUrl(final String endpoint, final String channel, final String ts, final String cursor,
            final Boolean inclusive, final String latest, final Integer limit, final String oldest) {
        final GenericUrl url = new GenericUrl(endpoint + "conversations.replies");
        if (channel != null) {
            url.put("channel", channel);
        }
        if (ts != null) {
            url.put("ts", ts);
        }
        if (cursor != null) {
            url.put("cursor", cursor);
        }
        if (inclusive != null) {
            url.put("inclusive", inclusive);
        }
        if (latest != null) {
            url.put("latest", latest);
        }
        if (limit != null) {
            url.put("limit", limit);
        }
        if (oldest != null) {
            url.put("oldest", oldest);
        }
        return url;
    }

}