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
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents a Slack message attachment containing additional formatted content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Attachment {

    /**
     * Default constructor.
     */
    public Attachment() {
    }

    /** Fallback text displayed when rich formatting is not supported. */
    protected String fallback;
    /** Title of the attachment. */
    protected String title;
    /** Main text content of the attachment. */
    protected String text;

    /**
     * Returns the fallback text for this attachment.
     *
     * @return the fallback text
     */
    public String getFallback() {
        return fallback;
    }

    /**
     * Returns the title of this attachment.
     *
     * @return the attachment title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the main text content of this attachment.
     *
     * @return the attachment text
     */
    public String getText() {
        return text;
    }

}
