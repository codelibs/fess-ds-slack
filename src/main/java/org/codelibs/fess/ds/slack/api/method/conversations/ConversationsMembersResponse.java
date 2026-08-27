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
import org.codelibs.fess.ds.slack.api.type.ResponseMetadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response class for the conversations.members API method.
 * Contains the member user IDs of a channel, as a plain list of ID strings -- Slack does not
 * return member profiles from this endpoint, only their IDs -- and pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConversationsMembersResponse extends Response {

    /**
     * Default constructor.
     */
    public ConversationsMembersResponse() {
        super();
    }

    /** List of member user IDs returned by the API. */
    protected List<String> members;

    /** Metadata for pagination and response handling. */
    protected ResponseMetadata responseMetadata;

    /**
     * Gets the list of member user IDs.
     *
     * @return the list of member user IDs
     */
    public List<String> getMembers() {
        return members;
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
