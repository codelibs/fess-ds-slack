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
 * Response class for the conversations.history Slack Web API method.
 * Contains the list of messages from a channel along with pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConversationsHistoryResponse extends Response {

    /**
     * Default constructor.
     */
    public ConversationsHistoryResponse() {
        super();
    }

    /** List of messages retrieved from the channel */
    protected List<Message> messages;
    /** Metadata for pagination including next cursor */
    protected ResponseMetadata responseMetadata;
    /** Indicates if more messages are available for pagination */
    protected Boolean hasMore;

    /**
     * Gets the list of messages retrieved from the channel.
     *
     * @return list of messages, may be null if no messages found
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * Gets the pagination metadata including next cursor.
     *
     * @return response metadata for pagination, may be null
     */
    public ResponseMetadata getResponseMetadata() {
        return responseMetadata;
    }

    /**
     * Gets the raw hasMore flag indicating if more messages are available.
     *
     * @return Boolean indicating more messages availability, may be null
     */
    public Boolean getHasMore() {
        return hasMore;
    }

    /**
     * Checks if more messages are available for pagination.
     *
     * @return true if more messages are available, false otherwise
     */
    public boolean hasMore() {
        return hasMore == null ? false : hasMore;
    }

}
