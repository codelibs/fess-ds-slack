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
package org.codelibs.fess.ds.slack.api.method.files;

import java.io.IOException;
import java.util.Scanner;

import org.codelibs.fess.ds.slack.SlackDataStoreException;
import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.ds.slack.api.SlackClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

public class FilesListRequest extends Request<FilesListResponse> {

    protected String channel, types, user;
    protected Integer count, page;
    protected Long tsFrom, tsTo;

    public FilesListRequest(final SlackClient client) {
        super(client);
    }

    @Override
    public FilesListResponse execute() {
        final StringBuilder result = new StringBuilder();
        final GenericUrl url = buildUrl(client.endpoint(), channel, count, page, tsFrom, tsTo, types, user);
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
            return mapper.readValue(json, FilesListResponse.class);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + json + "\"", e);
        }
    }

    public FilesListRequest channel(final String channel) {
        this.channel = channel;
        return this;
    }

    public FilesListRequest count(final Integer count) {
        this.count = count;
        return this;
    }

    public FilesListRequest page(final Integer page) {
        this.page = page;
        return this;
    }

    public FilesListRequest tsFrom(final Long tsFrom) {
        this.tsFrom = tsFrom;
        return this;
    }

    public FilesListRequest tsTo(final Long tsTo) {
        this.tsTo = tsTo;
        return this;
    }

    public FilesListRequest types(final String types) {
        this.types = types;
        return this;
    }

    public FilesListRequest user(final String user) {
        this.user = user;
        return this;
    }

    protected GenericUrl buildUrl(final String endpoint, final String channel, final Integer count, final Integer page, final Long tsFrom,
            final Long tsTo, final String types, final String user) {
        final GenericUrl url = new GenericUrl(endpoint + "files.list");
        if (channel != null) {
            url.put("channel", channel);
        }
        if (count != null) {
            url.put("count", count);
        }
        if (page != null) {
            url.put("page", page);
        }
        if (tsFrom != null) {
            url.put("ts_from", tsFrom);
        }
        if (tsTo != null) {
            url.put("ts_to", tsTo);
        }
        if (types != null) {
            url.put("types", types);
        }
        if (user != null) {
            url.put("user", user);
        }
        return url;
    }

}