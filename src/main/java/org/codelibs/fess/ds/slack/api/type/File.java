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
package org.codelibs.fess.ds.slack.api.type;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class File {

    protected String id;
    protected Long timestamp;
    protected String name;
    protected String title;
    protected String mimetype;
    protected String user;
    protected Long size;

    @JsonProperty("url_private")
    protected String urlPrivate;

    @JsonProperty("url_private_download")
    protected String urlPrivateDownload;

    protected String permalink;

    @JsonProperty("thumb_64")
    protected String thumb64;

    @JsonProperty("thumb_80")
    protected String thumb80;

    @JsonProperty("thumb_360")
    protected String thumb360;

    protected String preview;

    @JsonProperty("preview_highlight")
    protected String previewHighlight;

    public String getId() {
        return id;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getMimetype() {
        return mimetype;
    }

    public String getUser() {
        return user;
    }

    public Long getSize() {
        return size;
    }

    public String getUrlPrivate() {
        return urlPrivate;
    }

    public String getUrlPrivateDownload() {
        return urlPrivateDownload;
    }

    public String getPermalink() {
        return permalink;
    }

    public String getThumb64() {
        return thumb64;
    }

    public String getThumb80() {
        return thumb80;
    }

    public String getThumb360() {
        return thumb360;
    }

    public String getPreview() {
        return preview;
    }

    public String getPreviewHighlight() {
        return previewHighlight;
    }

}
