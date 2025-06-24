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

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FilesListResponse extends Response {

    protected List<File> files;

    protected Paging paging;

    public List<File> getFiles() {
        return files;
    }

    public Paging getPaging() {
        return paging;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Paging {
        protected Integer count;
        protected Integer total;
        protected Integer page;
        protected Integer pages;

        public Integer getCount() {
            return count;
        }

        public Integer getTotal() {
            return total;
        }

        public Integer getPage() {
            return page;
        }

        public Integer getPages() {
            return pages;
        }
    }

}
