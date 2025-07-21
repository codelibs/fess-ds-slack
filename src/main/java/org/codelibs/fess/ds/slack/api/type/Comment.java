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
 * Represents a comment on a file or other content in Slack.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Comment {

    /**
     * Default constructor.
     */
    public Comment() {
    }

    /** Unique identifier for the comment. */
    protected String id;
    /** Timestamp when the comment was created. */
    protected Long created;
    /** Timestamp of the comment. */
    protected Long timestamp;
    /** User ID of the comment author. */
    protected String user;
    /** The comment text content. */
    protected String comment;

    /**
     * Returns the unique identifier of this comment.
     *
     * @return the comment ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the timestamp when this comment was created.
     *
     * @return the creation timestamp
     */
    public Long getCreated() {
        return created;
    }

    /**
     * Returns the timestamp of this comment.
     *
     * @return the comment timestamp
     */
    public Long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the user ID of the comment author.
     *
     * @return the user ID
     */
    public String getUser() {
        return user;
    }

    /**
     * Returns the text content of this comment.
     *
     * @return the comment text
     */
    public String getComment() {
        return comment;
    }

}
