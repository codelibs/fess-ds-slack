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

public class SlackDataStoreFieldValueTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    /** A null fallback must be skipped, not rendered as the text "null". */
    @Test
    public void test_nullFallbackIsSkipped() {
        final Message message =
                firstMessage("{\"ok\":true,\"messages\":[{\"attachments\":[" + "{\"fallback\":\"FIRST\"},{},{\"fallback\":\"THIRD\"}]}]}");
        assertEquals("FIRST\nTHIRD", dataStore.getMessageAttachmentsText(message));
    }

    @Test
    public void test_allNullFallbacksProduceEmptyString() {
        final Message message = firstMessage("{\"ok\":true,\"messages\":[{\"attachments\":[{},{}]}]}");
        assertEquals("", dataStore.getMessageAttachmentsText(message));
    }

    @Test
    public void test_absentAttachmentsProduceEmptyString() {
        final Message message = firstMessage("{\"ok\":true,\"messages\":[{\"text\":\"TEXT\"}]}");
        assertEquals("", dataStore.getMessageAttachmentsText(message));
    }

    private Message firstMessage(final String content) {
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        return response.getMessages().get(0);
    }
}
