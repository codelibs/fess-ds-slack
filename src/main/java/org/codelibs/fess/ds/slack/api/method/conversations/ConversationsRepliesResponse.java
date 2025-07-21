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
package org.codelibs.fess.ds.slack.api.method.conversations;

import java.util.List;

import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.ResponseMetadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response class for the conversations.replies API method.
 * Contains thread messages and pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConversationsRepliesResponse extends Response {

    /**
     * Default constructor.
     */
    public ConversationsRepliesResponse() {
        super();
    }

    /** List of messages in the thread */
    protected List<Message> messages;

    /** Metadata for pagination and response handling */
    protected ResponseMetadata responseMetadata;

    /** Indicates if there are more messages available */
    protected Boolean hasMore;

    /**
     * Gets the list of messages in the thread.
     *
     * @return the list of messages
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * Gets the response metadata for pagination.
     *
     * @return the response metadata
     */
    public ResponseMetadata getResponseMetadata() {
        return responseMetadata;
    }

    /**
     * Checks if there are more messages available for pagination.
     *
     * @return true if more messages are available, false otherwise
     */
    public boolean hasMore() {
        return hasMore == null ? false : hasMore;
    }

}
