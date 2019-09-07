/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
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

import org.codelibs.fess.ds.slack.api.AbstractClient;

public class ConversationsClient extends AbstractClient {

    public ConversationsClient(final String token) {
        super(token);
    }

    public ConversationsListRequest list() {
        return new ConversationsListRequest(token);
    }

    public ConversationsHistoryRequest history(final String channel) {
        return new ConversationsHistoryRequest(token, channel);
    }

    public ConversationsInfoRequest info(final String channel) {
        return new ConversationsInfoRequest(token, channel);
    }

    public ConversationsRepliesRequest replies(final String channel, final String ts) {
        return new ConversationsRepliesRequest(token, channel, ts);
    }

}
