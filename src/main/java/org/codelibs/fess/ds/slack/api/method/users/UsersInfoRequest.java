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

public class UsersInfoRequest extends Request<UsersInfoResponse> {

    protected final String user;
    protected Boolean includeLocale;

    public UsersInfoRequest(final SlackClient client, final String user) {
        super(client);
        this.user = user;
    }

    @Override
    public UsersInfoResponse execute() {
        final StringBuilder result = new StringBuilder();
        final GenericUrl url = buildUrl(client.endpoint(), user, includeLocale);
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
            return mapper.readValue(json, UsersInfoResponse.class);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + json + "\"", e);
        }
    }

    public UsersInfoRequest includeLocale(final Boolean includeLocale) {
        this.includeLocale = includeLocale;
        return this;
    }

    protected GenericUrl buildUrl(final String endpoint, final String user, final Boolean includeLocale) {
        final GenericUrl url = new GenericUrl(endpoint + "users.info");
        if (user != null) {
            url.put("user", user);
        }
        if (includeLocale != null) {
            url.put("include_locale", includeLocale);
        }
        return url;
    }

}