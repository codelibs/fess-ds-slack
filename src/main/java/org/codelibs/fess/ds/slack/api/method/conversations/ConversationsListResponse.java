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
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.ResponseMetadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response class for the conversations.list API method.
 * Contains a list of channels and pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConversationsListResponse extends Response {

    /**
     * Default constructor.
     */
    public ConversationsListResponse() {
        super();
    }

    /** List of channels returned by the API */
    protected List<Channel> channels;

    /** Metadata for pagination and response handling */
    protected ResponseMetadata responseMetadata;

    /**
     * Gets the list of channels.
     *
     * @return the list of channels
     */
    public List<Channel> getChannels() {
        return channels;
    }

    /**
     * Gets the response metadata for pagination.
     *
     * @return the response metadata
     */
    public ResponseMetadata getResponseMetadata() {
        return responseMetadata;
    }

}
