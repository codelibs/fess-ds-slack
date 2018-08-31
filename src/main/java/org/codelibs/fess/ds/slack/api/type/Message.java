/*
 * Copyright 2012-2018 CodeLibs Project and the Others.
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
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    protected String type;
    protected String ts;
    protected String user;
    protected String text;
    protected String subtype;
    protected String username;
    protected String permalink;

    @JsonProperty("bot_id")
    protected String botId;

    @JsonProperty("thread_ts")
    protected String threadTs;

    protected List<File> files;

    @JsonProperty("is_thread_broadcast")
    protected Boolean isThreadBroadcast;

    public String getType() {
        return type;
    }

    public String getTs() {
        return ts;
    }

    public String getUser() {
        return user;
    }

    public String getText() {
        return text;
    }

    public String getSubtype() {
        return subtype;
    }

    public String getUsername() {
        return username;
    }

    public String getPermalink() {
        return permalink;
    }

    public String getBotId() {
        return botId;
    }

    public String getThreadTs() {
        return threadTs;
    }

    public List<File> getFiles() {
        return files;
    }

    public Boolean isThreadBroadcast() {
        return isThreadBroadcast != null ? isThreadBroadcast : false;
    }

}
