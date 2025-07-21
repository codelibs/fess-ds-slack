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
package org.codelibs.fess.ds.slack.api.method.users;

import java.util.List;

import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.type.ResponseMetadata;
import org.codelibs.fess.ds.slack.api.type.User;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Response from the users.list API method containing a list of users and pagination metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class UsersListResponse extends Response {

    /**
     * Default constructor.
     */
    public UsersListResponse() {
        super();
    }

    /** List of users returned by the API. */
    protected List<User> members;
    /** Metadata for pagination including next cursor. */
    protected ResponseMetadata responseMetadata;

    /**
     * Returns the list of users.
     *
     * @return the list of users
     */
    public List<User> getMembers() {
        return members;
    }

    /**
     * Returns the pagination metadata.
     *
     * @return the response metadata for pagination
     */
    public ResponseMetadata getResponseMetadata() {
        return responseMetadata;
    }

}
