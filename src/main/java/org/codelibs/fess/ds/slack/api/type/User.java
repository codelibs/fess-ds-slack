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
 * Represents a Slack user with identification and profile information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class User {

    /**
     * Default constructor for User.
     */
    public User() {
        // Default constructor
    }

    /** Unique identifier for the user. */
    protected String id;
    /** Username of the user. */
    protected String name;
    /** Whether the user has been deleted. */
    protected Boolean deleted;
    /** Real name of the user. */
    protected String realName;

    /** Detailed profile information for the user. */
    protected Profile profile;

    /**
     * Returns the unique identifier of this user.
     *
     * @return the user ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the username of this user.
     *
     * @return the username
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether this user has been deleted.
     *
     * @return true if the user is deleted, false otherwise
     */
    public boolean isDeleted() {
        return deleted == null ? false : deleted;
    }

    /**
     * Returns the real name of this user.
     *
     * @return the real name
     */
    public String getRealName() {
        return realName;
    }

    /**
     * Returns the detailed profile information for this user.
     *
     * @return the user profile
     */
    public Profile getProfile() {
        return profile;
    }

}
