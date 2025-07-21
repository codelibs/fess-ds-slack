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
package org.codelibs.fess.ds.slack.api.type;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents a Slack file object containing file metadata and access URLs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class File {

    /**
     * Default constructor.
     */
    public File() {
    }

    /** Unique identifier for the file */
    protected String id;

    /** Unix timestamp when the file was uploaded */
    protected Long timestamp;

    /** Original filename */
    protected String name;

    /** Title of the file */
    protected String title;

    /** MIME type of the file */
    protected String mimetype;

    /** User ID of the file uploader */
    protected String user;

    /** File size in bytes */
    protected Long size;

    /** Private URL for accessing the file */
    protected String urlPrivate;

    /** Private download URL for the file */
    protected String urlPrivateDownload;

    /** Permalink URL to the file */
    protected String permalink;

    /** 64x64 pixel thumbnail URL */
    @JsonProperty("thumb_64")
    protected String thumb64;

    /** 80x80 pixel thumbnail URL */
    @JsonProperty("thumb_80")
    protected String thumb80;

    /** 360 pixel thumbnail URL */
    @JsonProperty("thumb_360")
    protected String thumb360;

    /** Text preview content for the file */
    protected String preview;

    /** Highlighted preview content with syntax highlighting */
    @JsonProperty("preview_highlight")
    protected String previewHighlight;

    /**
     * Gets the unique identifier for the file.
     *
     * @return the file ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the Unix timestamp when the file was uploaded.
     *
     * @return the upload timestamp
     */
    public Long getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the original filename.
     *
     * @return the filename
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the title of the file.
     *
     * @return the file title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the MIME type of the file.
     *
     * @return the MIME type
     */
    public String getMimetype() {
        return mimetype;
    }

    /**
     * Gets the user ID of the file uploader.
     *
     * @return the user ID
     */
    public String getUser() {
        return user;
    }

    /**
     * Gets the file size in bytes.
     *
     * @return the file size
     */
    public Long getSize() {
        return size;
    }

    /**
     * Gets the private URL for accessing the file.
     *
     * @return the private URL
     */
    public String getUrlPrivate() {
        return urlPrivate;
    }

    /**
     * Gets the private download URL for the file.
     *
     * @return the private download URL
     */
    public String getUrlPrivateDownload() {
        return urlPrivateDownload;
    }

    /**
     * Gets the permalink URL to the file.
     *
     * @return the permalink URL
     */
    public String getPermalink() {
        return permalink;
    }

    /**
     * Gets the 64x64 pixel thumbnail URL.
     *
     * @return the 64x64 thumbnail URL
     */
    public String getThumb64() {
        return thumb64;
    }

    /**
     * Gets the 80x80 pixel thumbnail URL.
     *
     * @return the 80x80 thumbnail URL
     */
    public String getThumb80() {
        return thumb80;
    }

    /**
     * Gets the 360 pixel thumbnail URL.
     *
     * @return the 360 pixel thumbnail URL
     */
    public String getThumb360() {
        return thumb360;
    }

    /**
     * Gets the text preview content for the file.
     *
     * @return the preview content
     */
    public String getPreview() {
        return preview;
    }

    /**
     * Gets the highlighted preview content with syntax highlighting.
     *
     * @return the highlighted preview content
     */
    public String getPreviewHighlight() {
        return previewHighlight;
    }

}
