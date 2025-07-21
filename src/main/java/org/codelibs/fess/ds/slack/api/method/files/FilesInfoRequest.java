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

/**
 * Request class for the files.info API method.
 * Retrieves information about a specific file.
 */
public class FilesInfoRequest extends Request<FilesInfoResponse> {

    /** The file ID to retrieve information for */
    protected final String file;

    /** Number of items to return for paginated results */
    protected Integer count;

    /** Maximum number of items to return */
    protected Integer limit;

    /** Page number for pagination */
    protected Integer page;

    /** Pagination cursor for retrieving more results */
    protected String cursor;

    /**
     * Constructs a new files.info request.
     *
     * @param authentication the authentication credentials
     * @param file the file ID to retrieve information for
     */
    public FilesInfoRequest(final Authentication authentication, final String file) {
        super(authentication);
        this.file = file;
    }

    /**
     * Executes the files.info API request.
     *
     * @return the response containing file information
     */
    @Override
    public FilesInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), FilesInfoResponse.class);
    }

    /**
     * Sets the number of items to return for paginated results.
     *
     * @param count the number of items to return
     * @return this request instance for method chaining
     */
    public FilesInfoRequest count(final Integer count) {
        this.count = count;
        return this;
    }

    /**
     * Sets the pagination cursor for retrieving more results.
     *
     * @param cursor the pagination cursor
     * @return this request instance for method chaining
     */
    public FilesInfoRequest cursor(final String cursor) {
        this.cursor = cursor;
        return this;
    }

    /**
     * Sets the maximum number of items to return.
     *
     * @param limit the maximum number of items
     * @return this request instance for method chaining
     */
    public FilesInfoRequest limit(final Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets the page number for pagination.
     *
     * @param page the page number
     * @return this request instance for method chaining
     */
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
