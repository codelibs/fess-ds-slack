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

import java.util.List;

import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.type.File;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response class for the files.list API method.
 * Contains a list of files and pagination information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FilesListResponse extends Response {

    /**
     * Default constructor.
     */
    public FilesListResponse() {
        super();
    }

    /** List of files returned by the API */
    protected List<File> files;

    /** Pagination information for the response */
    protected Paging paging;

    /**
     * Gets the list of files.
     *
     * @return the list of files
     */
    public List<File> getFiles() {
        return files;
    }

    /**
     * Gets the pagination information.
     *
     * @return the paging object
     */
    public Paging getPaging() {
        return paging;
    }

    /**
     * Pagination information for files.list response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Paging {

        /**
         * Default constructor.
         */
        public Paging() {
        }

        /** Number of files in current page */
        protected Integer count;

        /** Total number of files available */
        protected Integer total;

        /** Current page number */
        protected Integer page;

        /** Total number of pages available */
        protected Integer pages;

        /**
         * Gets the number of files in current page.
         *
         * @return the count of files in current page
         */
        public Integer getCount() {
            return count;
        }

        /**
         * Gets the total number of files available.
         *
         * @return the total number of files
         */
        public Integer getTotal() {
            return total;
        }

        /**
         * Gets the current page number.
         *
         * @return the current page number
         */
        public Integer getPage() {
            return page;
        }

        /**
         * Gets the total number of pages available.
         *
         * @return the total number of pages
         */
        public Integer getPages() {
            return pages;
        }
    }

}
