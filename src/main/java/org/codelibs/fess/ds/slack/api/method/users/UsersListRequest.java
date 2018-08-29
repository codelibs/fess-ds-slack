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
package org.codelibs.fess.ds.slack.api.method.users;

import java.io.IOException;
import java.util.Scanner;

import org.codelibs.fess.ds.slack.SlackDataStoreException;
import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.ds.slack.api.SlackClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

public class UsersListRequest extends Request<UsersListResponse> {

    protected String cursor;
    protected Boolean includeLocale, presence;
    protected Integer limit;

    public UsersListRequest(final SlackClient client) {
        super(client);
    }

    @Override
    public UsersListResponse execute() {
        final StringBuilder result = new StringBuilder();
        final GenericUrl url = buildUrl(client.endpoint(), cursor, includeLocale, limit, presence);
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
            return mapper.readValue(json, UsersListResponse.class);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + json + "\"", e);
        }
    }

    public UsersListRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    public UsersListRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

    public UsersListRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    public UsersListRequest presence(final Boolean presence) {
        this.presence = presence;
        return this;
    }

    protected GenericUrl buildUrl(final String endpoint, final String cursor, final Boolean includeLocale, final Integer limit,
            final Boolean presence) {
        final GenericUrl url = new GenericUrl(endpoint + "users.list");
        if (cursor != null) {
            url.put("cursor", cursor);
        }
        if (includeLocale != null) {
            url.put("include_locale", includeLocale);
        }
        if (limit != null) {
            url.put("limit", limit);
        }
        if (presence != null) {
            url.put("presence", presence);
        }
        return url;
    }

}