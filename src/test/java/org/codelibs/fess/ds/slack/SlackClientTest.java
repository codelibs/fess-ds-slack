/*
 * Copyright 2012-2022 CodeLibs Project and the Others.
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
import org.codelibs.fess.entity.DataStoreParams;
import org.dbflute.utflute.lastaflute.LastaFluteTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackClientTest extends LastaFluteTestCase {

    private static Logger logger = LoggerFactory.getLogger(SlackClientTest.class);

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
        final SlackClient client = new SlackClient(new DataStoreParams());
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
        logger.info("----------ConversationsList----------");
        logger.info("Channels: ");
        for (final Channel channel : client.conversationsList().limit(5).execute().getChannels()) {
            logger.info("#" + channel.getName());
        }
    }

    protected void doConversationsHistoryTest(final SlackClient client) {
        logger.info("----------ConversationsHistory----------");
        final Channel channel = client.conversationsList().limit(1).execute().getChannels().get(0);
        logger.info("History of #" + channel.getName());
        final ConversationsHistoryResponse response = client.conversationsHistory(channel.getId()).limit(5).execute();
        for (final Message message : response.getMessages()) {
            logger.info(message.getUser() + ": " + message.getText());
        }
        logger.info("hasMore: " + response.hasMore());
    }

    protected void doConversationsInfoTest(final SlackClient client) {
        logger.info("----------ConversationsInfo----------");
        final String id = client.conversationsList().limit(1).execute().getChannels().get(0).getId();
        logger.info("Channel: " + id);
        final Channel channel = client.conversationsInfo(id).execute().getChannel();
        logger.info("#" + channel.getName());
    }

    protected void doConversationsRepliesTest(final SlackClient client, final String channel, final String ts) {
        logger.info("----------ConversationsReplies----------");
        final ConversationsRepliesResponse response = client.conversationsReplies(channel, ts).execute();
        final List<Message> messages = response.getMessages();
        for (int i = 1; i < messages.size(); i++) {
            logger.info(messages.get(i).getUser() + ": " + messages.get(i).getText());
        }
        logger.info("hasMore: " + response.hasMore());
    }

    protected void doUsersListTest(final SlackClient client) {
        logger.info("----------UsersList----------");
        logger.info("Users: ");
        final UsersListResponse response = client.usersList().limit(5).execute();
        for (final User user : response.getMembers()) {
            logger.info(user.getProfile().getDisplayName());
        }
        logger.info("next_cursor: " + response.getResponseMetadata().getNextCursor());
    }

    protected void doUsersInfoTest(final SlackClient client) {
        logger.info("----------UsersInfo----------");
        final String id = client.usersList().limit(1).execute().getMembers().get(0).getId();
        logger.info("User: " + id);
        final User user = client.usersInfo(id).execute().getUser();
        logger.info(user.getProfile().getDisplayName());
    }

    protected void doFilesListTest(final SlackClient client) {
        logger.info("----------FilesList----------");
        logger.info("Files: ");
        final FilesListResponse response = client.filesList().count(5).execute();
        for (final File file : response.getFiles()) {
            logger.info(file.getName() + "  " + file.getMimetype());
        }
        logger.info("count: " + response.getPaging().getCount());
    }

    protected void doFilesInfoTest(final SlackClient client) {
        logger.info("----------FilesInfo----------");
        final String id = client.filesList().count(1).execute().getFiles().get(0).getId();
        logger.info("File: " + id);
        final File file = client.filesInfo(id).execute().getFile();
        logger.info(file.getName() + "  " + file.getMimetype());
    }

    protected void doBotsInfoTest(final SlackClient client, final String id) {
        logger.info("----------BotsInfo----------");
        logger.info("Bot: " + id);
        final Bot bot = client.botsInfo().bot(id).execute().getBot();
        logger.info(bot.getName());
    }

    protected void doChatGetPermalinkTest(final SlackClient client, final String channel, final String ts) {
        logger.info("----------ChatGetPermalink----------");
        logger.info("Channel: " + channel + ", Timestamp: " + ts);
        final String link = client.chatGetPermalink(channel, ts).execute().getPermalink();
        logger.info(link);
    }

    protected void doTeamInfoTest(final SlackClient client) {
        logger.info("----------TeamInfo----------");
        logger.info("Team: ");
        final Team team = client.teamInfo().execute().getTeam();
        logger.info(team.getName() + " https://" + team.getDomain() + ".slack.com/");
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
        final ConversationsHistoryRequest request = new ConversationsHistoryRequest(null, null);
        final ConversationsHistoryResponse response = request.parseResponse(content, ConversationsHistoryResponse.class);
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
