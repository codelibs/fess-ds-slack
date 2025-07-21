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

import java.util.Map;

import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.type.File;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response class for the files.info API method.
 * Contains file information and pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FilesInfoResponse extends Response {

    /**
     * Default constructor.
     */
    public FilesInfoResponse() {
        super();
    }

    /** The file information */
    protected File file;

    /**
     * Gets the file information.
     *
     * @return the file object
     */
    public File getFile() {
        return file;
    }

    /** Metadata for pagination and response handling */
    protected Map<String, Object> responseMetadata;

    /**
     * Gets the response metadata for pagination.
     *
     * @return the response metadata map
     */
    public Map<String, Object> getResponseMetadata() {
        return responseMetadata;
    }

    /**
     * Gets the next pagination cursor from response metadata.
     *
     * @return the next cursor string, or null if not available
     */
    public String getNextCursor() {
        return (String) responseMetadata.get("next_cursor");
    }

}
