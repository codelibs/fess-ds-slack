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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents a Slack channel with metadata and member information.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Channel {

    /**
     * Default constructor.
     */
    public Channel() {
    }

    /** Unique identifier for the channel. */
    protected String id;
    /** Display name of the channel. */
    protected String name;
    /** Whether this is a channel (as opposed to a direct message). */
    protected Boolean isChannel;
    /** Whether the channel has been archived. */
    protected Boolean isArchived;
    /** Whether the channel is private. */
    protected Boolean isPrivate;

    /** List of member user IDs in the channel. */
    protected List<String> members;

    /**
     * Returns the unique identifier of this channel.
     *
     * @return the channel ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the display name of this channel.
     *
     * @return the channel name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether this is a channel (as opposed to a direct message).
     *
     * @return true if this is a channel, false otherwise
     */
    public boolean isChannel() {
        return isChannel == null ? false : isChannel;
    }

    /**
     * Returns whether this channel has been archived.
     *
     * @return true if the channel is archived, false otherwise
     */
    public boolean isArchived() {
        return isArchived == null ? false : isArchived;
    }

    /**
     * Returns whether this channel is private.
     *
     * @return true if the channel is private, false otherwise
     */
    public boolean isPrivate() {
        return isPrivate == null ? false : isPrivate;
    }

    /**
     * Returns the list of member user IDs in this channel.
     *
     * @return the list of member IDs
     */
    public List<String> getMembers() {
        return members;
    }

}
