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
package org.codelibs.fess.ds.slack.api.method.conversations;

import java.util.List;
import java.util.Map;

import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.type.Message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationsRepliesResponse extends Response {

    protected List<Message> messages;

    @JsonProperty("response_metadata")
    protected Map<String, Object> responseMetadata;

    @JsonProperty("has_more")
    protected Boolean hasMore;

    public List<Message> getMessages() {
        return messages;
    }

    public Map<String, Object> getResponseMetadata() {
        return responseMetadata;
    }

    public String getNextCursor() {
        return (String) responseMetadata.get("next_cursor");
    }

    public Boolean hasMore() {
        return hasMore;
    }

}