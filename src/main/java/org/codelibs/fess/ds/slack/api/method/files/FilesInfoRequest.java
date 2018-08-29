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

public class FilesInfoRequest extends Request<FilesInfoResponse> {

    protected final String file;
    protected Integer count, limit, page;
    protected String cursor;

    public FilesInfoRequest(final SlackClient client, final String file) {
        super(client);
        this.file = file;
    }

    @Override
    public FilesInfoResponse execute() {
        final StringBuilder result = new StringBuilder();
        final GenericUrl url = buildUrl(client.endpoint(), file, count, cursor, limit, page);
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
            return mapper.readValue(json, FilesInfoResponse.class);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + json + "\"", e);
        }
    }

    public FilesInfoRequest count(final Integer count) {
        this.count = count;
        return this;
    }

    public FilesInfoRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    public FilesInfoRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    public FilesInfoRequest page(final Integer page) {
        this.page = page;
        return this;
    }

    protected GenericUrl buildUrl(final String endpoint, final String file, final Integer count, final String cursor, final Integer limit,
            final Integer page) {
        final GenericUrl url = new GenericUrl(endpoint + "files.info");
        if (file != null) {
            url.put("file", file);
        }
        if (count != null) {
            url.put("count", count);
        }
        if (cursor != null) {
            url.put("cursor", cursor);
        }
        if (limit != null) {
            url.put("limit", limit);
        }
        if (page != null) {
            url.put("page", page);
        }
        return url;
    }

}