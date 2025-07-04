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
package org.codelibs.fess.ds.slack.api.method.files;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.Request;

public class FilesInfoRequest extends Request<FilesInfoResponse> {

    protected final String file;
    protected Integer count;
    protected Integer limit;
    protected Integer page;
    protected String cursor;

    public FilesInfoRequest(final Authentication authentication, final String file) {
        super(authentication);
        this.file = file;
    }

    @Override
    public FilesInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), FilesInfoResponse.class);
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

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "files.info");
        if (file != null) {
            request.param("file", file);
        }
        if (count != null) {
            request.param("count", count.toString());
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        if (page != null) {
            request.param("page", page.toString());
        }
        return request;
    }

}
