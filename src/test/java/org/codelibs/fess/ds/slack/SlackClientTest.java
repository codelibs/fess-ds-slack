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
package org.codelibs.fess.ds.slack;

import java.util.HashMap;
import java.util.List;

import org.codelibs.fess.ds.slack.api.method.bots.BotsInfoRequest;
import org.codelibs.fess.ds.slack.api.method.bots.BotsInfoResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesResponse;
import org.codelibs.fess.ds.slack.api.method.files.FilesListRequest;
import org.codelibs.fess.ds.slack.api.method.files.FilesListResponse;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoRequest;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.codelibs.fess.ds.slack.api.method.users.UsersListRequest;
import org.codelibs.fess.ds.slack.api.method.users.UsersListResponse;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Bot;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.dbflute.utflute.lastaflute.LastaFluteTestCase;

public class SlackClientTest extends LastaFluteTestCase {

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

    public void testProduction() {
        // doProductionTest();
    }

    protected void doProductionTest() {
        final SlackClient client = new SlackClient(new HashMap<>());
        doConversationsListTest(client);
        doConversationsHistoryTest(client);
        doConversationsInfoTest(client);
        doUsersListTest(client);
        doUsersInfoTest(client);
        doFilesListTest(client);
        doFilesInfoTest(client);
        // doBotsInfoTest(client, "");
        // doChatGetPermalinkTest(client, "", "");
        // doConversationsRepliesTest(client, "", "");
        doTeamInfoTest(client);
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
        final ConversationsHistoryResponse response = client.conversations.history(channel.getId()).limit(5).execute();
        for (final Message message : response.getMessages()) {
            System.out.println(message.getUser() + ": " + message.getText());
        }
        System.out.println("hasMore: " + response.hasMore());
    }

    protected void doConversationsInfoTest(final SlackClient client) {
        System.out.println("----------ConversationsInfo----------");
        final String id = client.conversations.list().limit(1).execute().getChannels().get(0).getId();
        System.out.println("Channel: " + id);
        final Channel channel = client.conversations.info(id).execute().getChannel();
        System.out.println("#" + channel.getName());
    }

    protected void doConversationsRepliesTest(final SlackClient client, final String channel, final String ts) {
        System.out.println("----------ConversationsReplies----------");
        final ConversationsRepliesResponse response = client.conversations.replies(channel, ts).execute();
        final List<Message> messages = response.getMessages();
        for (int i = 1; i < messages.size(); i++) {
            System.out.println(messages.get(i).getUser() + ": " + messages.get(i).getText());
        }
        System.out.println("hasMore: " + response.hasMore());
    }

    protected void doUsersListTest(final SlackClient client) {
        System.out.println("----------UsersList----------");
        System.out.println("Users: ");
        final UsersListResponse response = client.users.list().limit(5).execute();
        for (final User user : response.getMembers()) {
            System.out.println(user.getProfile().getDisplayName());
        }
        System.out.println("next_cursor: " + response.getResponseMetadata().getNextCursor());
    }

    protected void doUsersInfoTest(final SlackClient client) {
        System.out.println("----------UsersInfo----------");
        final String id = client.users.list().limit(1).execute().getMembers().get(0).getId();
        System.out.println("User: " + id);
        final User user = client.users.info(id).execute().getUser();
        System.out.println(user.getProfile().getDisplayName());
    }

    protected void doFilesListTest(final SlackClient client) {
        System.out.println("----------FilesList----------");
        System.out.println("Files: ");
        final FilesListResponse response = client.files.list().count(5).execute();
        for (final File file : response.getFiles()) {
            System.out.println(file.getName() + "  " + file.getMimetype());
        }
        System.out.println("count: " + response.getPaging().getCount());
    }

    protected void doFilesInfoTest(final SlackClient client) {
        System.out.println("----------FilesInfo----------");
        final String id = client.files.list().count(1).execute().getFiles().get(0).getId();
        System.out.println("File: " + id);
        final File file = client.files.info(id).execute().getFile();
        System.out.println(file.getName() + "  " + file.getMimetype());
    }

    protected void doBotsInfoTest(final SlackClient client, final String id) {
        System.out.println("----------BotsInfo----------");
        System.out.println("Bot: " + id);
        final Bot bot = client.bots.info().bot(id).execute().getBot();
        System.out.println(bot.getName());
    }

    protected void doChatGetPermalinkTest(final SlackClient client, final String channel, final String ts) {
        System.out.println("----------ChatGetPermalink----------");
        System.out.println("Channel: " + channel + ", Timestamp: " + ts);
        final String link = client.chat.getPermalink(channel, ts).execute().getPermalink();
        System.out.println(link);
    }

    protected void doTeamInfoTest(final SlackClient client) {
        System.out.println("----------TeamInfo----------");
        System.out.println("Team: ");
        final Team team = client.team.info().execute().getTeam();
        System.out.println(team.getName() + " https://" + team.getDomain() + ".slack.com/");
    }

    public void testConversationsList() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"channels\": [" + //
                "        {" + //
                "            \"id\": \"CHANNEL_ID0\"," + //
                "            \"name\": \"CHANNEL_Name0\"" + //
                "        }," + //
                "        {" + //
                "            \"id\": \"CHANNEL_ID1\"," + //
                "            \"name\": \"CHANNEL_Name1\"" + //
                "        }" + //
                "    ]," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"NEXT_CURSOR\"" + //
                "    }" + //
                "}";
        final ConversationsListResponse response =
                new ConversationsListRequest(null).parseResponse(content, ConversationsListResponse.class);
        assertTrue(response.ok());
        final List<Channel> channels = response.getChannels();
        for (int i = 0; i < channels.size(); i++) {
            assertEquals(channels.get(i).getId(), "CHANNEL_ID" + i);
            assertEquals(channels.get(i).getName(), "CHANNEL_Name" + i);
        }
        assertEquals(response.getResponseMetadata().getNextCursor(), "NEXT_CURSOR");
    }

    public void testConversationsHistory() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"messages\": [" + //
                "        {" + //
                "            \"user\": \"USER\"," + //
                "            \"text\": \"TEXT\"," + //
                "            \"ts\": \"1234567890.000100\"" + //
                "        }," + //
                "        {" + //
                "            \"attachments\": [" + //
                "                {" + //
                "                    \"fallback\": \"FALLBACK\"" + //
                "                }" + //
                "            ]," + //
                "            \"files\": [" + //
                "                {" + //
                "                    \"id\": \"FILE_ID\"" + //
                "                }" + //
                "            ]" + //
                "        }" + //
                "    ]," + //
                "    \"has_more\": true," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"NEXT_CURSOR\"" + //
                "    }" + //
                "}";
        ;
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        assertTrue(response.ok());
        final List<Message> messages = response.getMessages();
        assertEquals(messages.get(0).getUser(), "USER");
        assertEquals(messages.get(0).getText(), "TEXT");
        assertEquals(messages.get(0).getTs(), "1234567890.000100");
        final Attachment attach = messages.get(1).getAttachments().get(0);
        assertEquals(attach.getFallback(), "FALLBACK");
        final File file = messages.get(1).getFiles().get(0);
        assertEquals(file.getId(), "FILE_ID");
        assertTrue(response.hasMore());
        assertEquals(response.getResponseMetadata().getNextCursor(), "NEXT_CURSOR");
    }

    public void testUsersList() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"members\": [" + //
                "        {" + //
                "            \"id\": \"ID0\"," + //
                "            \"name\": \"NAME0\"," + //
                "            \"profile\": {" + //
                "                \"display_name\": \"DISPLAY_NAME0\"" + //
                "            }" + //
                "        }," + //
                "        {" + //
                "            \"id\": \"ID1\"," + //
                "            \"name\": \"NAME1\"," + //
                "            \"profile\": {" + //
                "                \"display_name\": \"DISPLAY_NAME1\"" + //
                "            }" + //
                "        }" + //
                "    ]" + //
                "}";
        final UsersListResponse response = new UsersListRequest(null).parseResponse(content, UsersListResponse.class);
        assertTrue(response.ok());
        final List<User> members = response.getMembers();
        for (int i = 0; i < members.size(); i++) {
            assertEquals(members.get(i).getId(), "ID" + i);
            assertEquals(members.get(i).getName(), "NAME" + i);
            assertEquals(members.get(i).getProfile().getDisplayName(), "DISPLAY_NAME" + i);
        }
    }

    public void testFilesList() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"files\": [" + //
                "        {" + //
                "            \"id\": \"FILE_ID0\"," + //
                "            \"timestamp\": 1234567890," + //
                "            \"thumb_360\": \"THUMBNAIL0\"" + //
                "        }," + //
                "        {" + //
                "            \"id\": \"FILE_ID1\"," + //
                "            \"timestamp\": 1234567890," + //
                "            \"thumb_360\": \"THUMBNAIL1\"" + //
                "        }" + //
                "    ]," + //
                "    \"paging\": {" + //
                "        \"count\": 2" + //
                "    }" + //
                "}";
        final FilesListResponse response = new FilesListRequest(null).parseResponse(content, FilesListResponse.class);
        assertTrue(response.ok());
        final List<File> files = response.getFiles();
        for (int i = 0; i < files.size(); i++) {
            assertEquals(files.get(i).getId(), "FILE_ID" + i);
            assertEquals(files.get(i).getTimestamp(), Long.valueOf(1234567890));
            assertEquals(files.get(i).getThumb360(), "THUMBNAIL" + i);
        }
        assertEquals(response.getPaging().getCount(), Integer.valueOf(2));
    }

    public void testBotsInfo() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"bot\": {" + //
                "        \"id\": \"BOT_ID\"," + //
                "        \"name\": \"BOT_NAME\"" + //
                "    }" + //
                "}";
        final BotsInfoResponse response = new BotsInfoRequest(null).parseResponse(content, BotsInfoResponse.class);
        assertTrue(response.ok());
        final Bot bot = response.getBot();
        assertEquals(bot.getId(), "BOT_ID");
        assertEquals(bot.getName(), "BOT_NAME");
    }

    public void testTeamInfo() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"team\": {" + //
                "        \"id\": \"TEAM_ID\"," + //
                "        \"name\": \"TEAM_NAME\"," + //
                "        \"domain\": \"TEAM_DOMAIN\"" + //
                "    }" + //
                "}";
        final TeamInfoResponse response = new TeamInfoRequest(null).parseResponse(content, TeamInfoResponse.class);
        assertTrue(response.ok());
        final Team team = response.getTeam();
        assertEquals(team.getId(), "TEAM_ID");
        assertEquals(team.getName(), "TEAM_NAME");
        assertEquals(team.getDomain(), "TEAM_DOMAIN");
    }

}
