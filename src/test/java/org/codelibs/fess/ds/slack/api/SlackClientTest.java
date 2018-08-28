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
package org.codelibs.fess.ds.slack.api;

import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.dbflute.utflute.lastadi.ContainerTestCase;

public class SlackClientTest extends ContainerTestCase {

    @Override
    protected String prepareConfigFile() {
        return "test_app.xml";
    }

    @Override
    protected boolean isSuppressTestCaseTransaction() {
        return true;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void test_production() {
        // doProductionTest();
    }

    protected void doProductionTest() {
        final SlackClient client = new SlackClient("");
        doConversationsListTest(client);
        doConversationsHistoryTest(client);
        doConversationsInfoTest(client);
    }

    protected void doConversationsListTest(final SlackClient client) {
        System.out.println("----------ConversationsList----------");
        System.out.println("Channels: ");
        for (final Channel channel : client.conversations.list().limit(5).execute().getChannels()) {
            System.out.println("#" + channel.getName());
        }
    }

    protected void doConversationsHistoryTest(final SlackClient client) {
        System.out.println("----------ConversationsHistory----------");
        final Channel channel = client.conversations.list().limit(1).execute().getChannels().get(0);
        System.out.println("History of #" + channel.getName());
        final ConversationsHistoryResponse response = client.conversations.history(channel.getId()).count(5).execute();
        for (final Message message : response.getMessages()) {
            System.out.println(message.getUser() + ": " + message.getText());
        }
        System.out.println("next_cursor: " + response.getNextCursor());
    }

    protected void doConversationsInfoTest(final SlackClient client) {
        System.out.println("----------ConversationsInfo----------");
        final String id = client.conversations.list().limit(1).execute().getChannels().get(0).getId();
        System.out.println("Channel: " + id);
        final Channel channel = client.conversations.info(id).execute().getChannel();
        System.out.println("#" + channel.getName());
    }

}
