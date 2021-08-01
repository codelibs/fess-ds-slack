/*
 * Copyright 2012-2021 CodeLibs Project and the Others.
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
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class ConversationsHistoryResponse extends Response {

    protected List<Message> messages;
    protected ResponseMetadata responseMetadata;
    protected Boolean hasMore;

    public List<Message> getMessages() {
        return messages;
    }

    public ResponseMetadata getResponseMetadata() {
        return responseMetadata;
    }

    public Boolean getHasMore() {
        return hasMore;
    }

    public boolean hasMore() {
        return hasMore == null ? false : hasMore;
    }

}
