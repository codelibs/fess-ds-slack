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

public class SlackDataStorePermalinkTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    /** A message with no ts must not throw; the permalink is simply unknown. */
    @Test
    public void test_messageWithoutTsDoesNotThrow() {
        final Message message = firstMessage("{\"ok\":true,\"messages\":[{\"text\":\"TEXT\"}]}");
        final String permalink = dataStore.getMessagePermalink(null, null, null, message);
        assertEquals("", permalink);
    }

    /**
     * The {@code ts == null} guard lives inside the "no permalink yet" branch
     * specifically so a message that already carries a permalink keeps it
     * even when {@code ts} is absent; only the both-absent case was covered
     * before this test.
     */
    @Test
    public void test_messageWithPermalinkButNoTsKeepsPermalink() {
        final Message message =
                firstMessage("{\"ok\":true,\"messages\":[{\"text\":\"TEXT\",\"permalink\":\"https://example.slack.com/archives/C1/p1\"}]}");
        final String permalink = dataStore.getMessagePermalink(null, null, null, message);
        assertEquals("https://example.slack.com/archives/C1/p1", permalink);
    }

    private Message firstMessage(final String content) {
        return new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class).getMessages().get(0);
    }
}
