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
package org.codelibs.fess.ds.slack.api.method.files;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.Request;

public class FilesListRequest extends Request<FilesListResponse> {

    protected String channel;
    protected String types;
    protected String user;
    protected Integer count;
    protected Integer page;
    protected Long tsFrom;
    protected Long tsTo;

    public FilesListRequest(final Authentication authentication) {
        super(authentication);
    }

    @Override
    public FilesListResponse execute() {
        return parseResponse(request().execute().getContentAsString(), FilesListResponse.class);
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

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "files.list");
        if (channel != null) {
            request.param("channel", channel);
        }
        if (count != null) {
            request.param("count", count.toString());
        }
        if (page != null) {
            request.param("page", page.toString());
        }
        if (tsFrom != null) {
            request.param("ts_from", tsFrom.toString());
        }
        if (tsTo != null) {
            request.param("ts_to", tsTo.toString());
        }
        if (types != null) {
            request.param("types", types);
        }
        if (user != null) {
            request.param("user", user);
        }
        return request;
    }

}
