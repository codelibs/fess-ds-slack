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
package org.codelibs.fess.ds.slack;

import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class SlackDataStoreThreadTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    /** Slack identifies a thread parent by thread_ts == ts. */
    @Test
    public void test_parentIsThreadTsEqualToTs() {
        assertTrue(dataStore.isThreadParent(message("{\"ts\":\"1.000100\",\"thread_ts\":\"1.000100\"}")));
    }

    /** A reply carries a different thread_ts and must not re-fetch the thread. */
    @Test
    public void test_replyIsNotThreadParent() {
        assertFalse(dataStore.isThreadParent(message("{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\"}")));
    }

    /** A broadcast reply is still a reply. */
    @Test
    public void test_broadcastReplyIsNotThreadParent() {
        assertFalse(dataStore.isThreadParent(message("{\"ts\":\"2.000200\",\"thread_ts\":\"1.000100\",\"subtype\":\"thread_broadcast\"}")));
    }

    /** A standalone message has no thread at all. */
    @Test
    public void test_standaloneMessageIsNotThreadParent() {
        assertFalse(dataStore.isThreadParent(message("{\"ts\":\"1.000100\"}")));
    }

    private Message message(final String json) {
        return new ConversationsHistoryRequest(null, null)
                .parseResponse("{\"ok\":true,\"messages\":[" + json + "]}", ConversationsHistoryResponse.class)
                .getMessages()
                .get(0);
    }
}
