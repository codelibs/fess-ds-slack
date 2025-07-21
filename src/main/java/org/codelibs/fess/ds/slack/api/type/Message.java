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
 * Represents a Slack message with all associated metadata, files, and attachments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Message {

    /**
     * Default constructor.
     */
    public Message() {
    }

    /** The type of message (e.g., "message"). */
    protected String type;
    /** Timestamp of the message. */
    protected String ts;
    /** User ID of the message author. */
    protected String user;
    /** Text content of the message. */
    protected String text;
    /** Subtype of the message (e.g., "bot_message", "file_share"). */
    protected String subtype;
    /** Permanent link to the message. */
    protected String permalink;
    /** Bot ID if the message was sent by a bot. */
    protected String botId;
    /** Thread timestamp if this message is part of a thread. */
    protected String threadTs;
    /** List of files attached to the message. */
    protected List<File> files;
    /** Whether this message was broadcast from a thread to the channel. */
    protected Boolean isThreadBroadcast;
    /** Comment associated with a file share. */
    protected Comment comment;
    /** List of message attachments with rich formatting. */
    protected List<Attachment> attachments;

    /**
     * Returns the type of this message.
     *
     * @return the message type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the timestamp of this message.
     *
     * @return the message timestamp
     */
    public String getTs() {
        return ts;
    }

    /**
     * Returns the user ID of the message author.
     *
     * @return the user ID
     */
    public String getUser() {
        return user;
    }

    /**
     * Returns the text content of this message.
     *
     * @return the message text
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the subtype of this message.
     *
     * @return the message subtype
     */
    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns the permanent link to this message.
     *
     * @return the message permalink
     */
    public String getPermalink() {
        return permalink;
    }

    /**
     * Returns the bot ID if this message was sent by a bot.
     *
     * @return the bot ID, or null if not sent by a bot
     */
    public String getBotId() {
        return botId;
    }

    /**
     * Returns the thread timestamp if this message is part of a thread.
     *
     * @return the thread timestamp, or null if not part of a thread
     */
    public String getThreadTs() {
        return threadTs;
    }

    /**
     * Returns the list of files attached to this message.
     *
     * @return the list of attached files
     */
    public List<File> getFiles() {
        return files;
    }

    /**
     * Returns whether this message was broadcast from a thread to the channel.
     *
     * @return true if broadcast from thread, false otherwise
     */
    public boolean isThreadBroadcast() {
        return isThreadBroadcast != null ? isThreadBroadcast : false;
    }

    /**
     * Returns the comment associated with a file share.
     *
     * @return the comment, or null if no comment
     */
    public Comment getComment() {
        return comment;
    }

    /**
     * Returns the list of message attachments with rich formatting.
     *
     * @return the list of attachments
     */
    public List<Attachment> getAttachments() {
        return attachments;
    }

}
