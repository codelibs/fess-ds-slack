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
 * Request class for the files.list API method.
 * Retrieves a list of files matching specified criteria.
 */
public class FilesListRequest extends Request<FilesListResponse> {

    /** Channel ID to filter files by */
    protected String channel;

    /** File types to include (comma-separated) */
    protected String types;

    /** User ID to filter files by */
    protected String user;

    /** Number of items to return per page */
    protected Integer count;

    /** Page number for pagination */
    protected Integer page;

    /** Filter files created after this timestamp */
    protected Long tsFrom;

    /** Filter files created before this timestamp */
    protected Long tsTo;

    /**
     * Constructs a new files.list request.
     *
     * @param authentication the authentication credentials
     */
    public FilesListRequest(final Authentication authentication) {
        super(authentication);
    }

    /**
     * Executes the files.list API request.
     *
     * @return the response containing the list of files
     */
    @Override
    public FilesListResponse execute() {
        return parseResponse(request().execute().getContentAsString(), FilesListResponse.class);
    }

    /**
     * Sets the channel ID to filter files by.
     *
     * @param channel the channel ID
     * @return this request instance for method chaining
     */
    public FilesListRequest channel(final String channel) {
        this.channel = channel;
        return this;
    }

    /**
     * Sets the number of items to return per page.
     *
     * @param count the number of items per page
     * @return this request instance for method chaining
     */
    public FilesListRequest count(final Integer count) {
        this.count = count;
        return this;
    }

    /**
     * Sets the page number for pagination.
     *
     * @param page the page number
     * @return this request instance for method chaining
     */
    public FilesListRequest page(final Integer page) {
        this.page = page;
        return this;
    }

    /**
     * Sets the timestamp to filter files created after.
     *
     * @param tsFrom the timestamp (Unix time)
     * @return this request instance for method chaining
     */
    public FilesListRequest tsFrom(final Long tsFrom) {
        this.tsFrom = tsFrom;
        return this;
    }

    /**
     * Sets the timestamp to filter files created before.
     *
     * @param tsTo the timestamp (Unix time)
     * @return this request instance for method chaining
     */
    public FilesListRequest tsTo(final Long tsTo) {
        this.tsTo = tsTo;
        return this;
    }

    /**
     * Sets the file types to include (comma-separated).
     *
     * @param types the file types
     * @return this request instance for method chaining
     */
    public FilesListRequest types(final String types) {
        this.types = types;
        return this;
    }

    /**
     * Sets the user ID to filter files by.
     *
     * @param user the user ID
     * @return this request instance for method chaining
     */
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
