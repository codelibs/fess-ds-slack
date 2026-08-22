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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.List;

import org.codelibs.fess.ds.slack.api.method.bots.BotsInfoRequest;
import org.codelibs.fess.ds.slack.api.method.bots.BotsInfoResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesRequest;
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
import org.codelibs.fess.ds.slack.UnitDsTestCase;

public class SlackClientTest extends UnitDsTestCase {

    @Override
    protected String prepareConfigFile() {
        return "test_app.xml";
    }

    @Override
    protected boolean isSuppressTestCaseTransaction() {
        return true;
    }

    @Override
    public void setUp(TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
    }

    @Override
    public void tearDown(TestInfo testInfo) throws Exception {
        super.tearDown(testInfo);
    }

    @Test
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
        // Asserted up front so a regression that empties the list (e.g. a broken Jackson
        // mapping) fails here instead of silently skipping every iteration below.
        assertEquals(2, channels.size());
        for (int i = 0; i < channels.size(); i++) {
            assertEquals("CHANNEL_ID" + i, channels.get(i).getId());
            assertEquals("CHANNEL_Name" + i, channels.get(i).getName());
        }
        assertEquals("NEXT_CURSOR", response.getResponseMetadata().getNextCursor());
    }

    @Test
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
        assertEquals("USER", messages.get(0).getUser());
        assertEquals("TEXT", messages.get(0).getText());
        assertEquals("1234567890.000100", messages.get(0).getTs());
        final Attachment attach = messages.get(1).getAttachments().get(0);
        assertEquals("FALLBACK", attach.getFallback());
        final File file = messages.get(1).getFiles().get(0);
        assertEquals("FILE_ID", file.getId());
        assertTrue(response.hasMore());
        assertEquals("NEXT_CURSOR", response.getResponseMetadata().getNextCursor());
    }

    @Test
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
        // Asserted up front so a regression that empties the list (e.g. a broken Jackson
        // mapping) fails here instead of silently skipping every iteration below.
        assertEquals(2, members.size());
        for (int i = 0; i < members.size(); i++) {
            assertEquals("ID" + i, members.get(i).getId());
            assertEquals("NAME" + i, members.get(i).getName());
            assertEquals("DISPLAY_NAME" + i, members.get(i).getProfile().getDisplayName());
        }
    }

    @Test
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
        // Asserted up front so a regression that empties the list (e.g. a broken Jackson
        // mapping) fails here instead of silently skipping every iteration below.
        assertEquals(2, files.size());
        for (int i = 0; i < files.size(); i++) {
            assertEquals("FILE_ID" + i, files.get(i).getId());
            assertEquals(Long.valueOf(1234567890), files.get(i).getTimestamp());
            assertEquals("THUMBNAIL" + i, files.get(i).getThumb360());
        }
        assertEquals(Integer.valueOf(2), response.getPaging().getCount());
    }

    @Test
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
        assertEquals("BOT_ID", bot.getId());
        assertEquals("BOT_NAME", bot.getName());
    }

    @Test
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
        assertEquals("TEAM_ID", team.getId());
        assertEquals("TEAM_NAME", team.getName());
        assertEquals("TEAM_DOMAIN", team.getDomain());
    }

    // Test error responses
    @Test
    public void testConversationsList_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"invalid_auth\"" + //
                "}";
        final ConversationsListResponse response =
                new ConversationsListRequest(null).parseResponse(content, ConversationsListResponse.class);
        assertFalse(response.ok());
    }

    @Test
    public void testConversationsHistory_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"channel_not_found\"" + //
                "}";
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        assertFalse(response.ok());
    }

    @Test
    public void testUsersList_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"invalid_auth\"" + //
                "}";
        final UsersListResponse response = new UsersListRequest(null).parseResponse(content, UsersListResponse.class);
        assertFalse(response.ok());
    }

    @Test
    public void testFilesList_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"not_authed\"" + //
                "}";
        final FilesListResponse response = new FilesListRequest(null).parseResponse(content, FilesListResponse.class);
        assertFalse(response.ok());
    }

    @Test
    public void testBotsInfo_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"bot_not_found\"" + //
                "}";
        final BotsInfoResponse response = new BotsInfoRequest(null).parseResponse(content, BotsInfoResponse.class);
        assertFalse(response.ok());
    }

    @Test
    public void testTeamInfo_errorResponse() {
        final String content = "" + //
                "{" + //
                "    \"ok\": false," + //
                "    \"error\": \"invalid_auth\"" + //
                "}";
        final TeamInfoResponse response = new TeamInfoRequest(null).parseResponse(content, TeamInfoResponse.class);
        assertFalse(response.ok());
    }

    // Test edge cases
    @Test
    public void testConversationsList_emptyChannels() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"channels\": []," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"\"" + //
                "    }" + //
                "}";
        final ConversationsListResponse response =
                new ConversationsListRequest(null).parseResponse(content, ConversationsListResponse.class);
        assertTrue(response.ok());
        final List<Channel> channels = response.getChannels();
        assertNotNull(channels);
        assertEquals(0, channels.size());
        assertEquals("", response.getResponseMetadata().getNextCursor());
    }

    @Test
    public void testConversationsHistory_emptyMessages() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"messages\": []," + //
                "    \"has_more\": false," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"\"" + //
                "    }" + //
                "}";
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        assertTrue(response.ok());
        final List<Message> messages = response.getMessages();
        assertNotNull(messages);
        assertEquals(0, messages.size());
        assertFalse(response.hasMore());
    }

    @Test
    public void testUsersList_emptyMembers() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"members\": []" + //
                "}";
        final UsersListResponse response = new UsersListRequest(null).parseResponse(content, UsersListResponse.class);
        assertTrue(response.ok());
        final List<User> members = response.getMembers();
        assertNotNull(members);
        assertEquals(0, members.size());
    }

    @Test
    public void testFilesList_emptyFiles() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"files\": []," + //
                "    \"paging\": {" + //
                "        \"count\": 0" + //
                "    }" + //
                "}";
        final FilesListResponse response = new FilesListRequest(null).parseResponse(content, FilesListResponse.class);
        assertTrue(response.ok());
        final List<File> files = response.getFiles();
        assertNotNull(files);
        assertEquals(0, files.size());
        assertEquals(Integer.valueOf(0), response.getPaging().getCount());
    }

    // Test multiple attachments in a single message
    @Test
    public void testConversationsHistory_multipleAttachments() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"messages\": [" + //
                "        {" + //
                "            \"user\": \"USER\"," + //
                "            \"text\": \"TEXT\"," + //
                "            \"ts\": \"1234567890.000100\"," + //
                "            \"attachments\": [" + //
                "                {" + //
                "                    \"fallback\": \"FALLBACK1\"" + //
                "                }," + //
                "                {" + //
                "                    \"fallback\": \"FALLBACK2\"" + //
                "                }" + //
                "            ]" + //
                "        }" + //
                "    ]," + //
                "    \"has_more\": false" + //
                "}";
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        assertTrue(response.ok());
        final List<Message> messages = response.getMessages();
        assertEquals(1, messages.size());
        final Message message = messages.get(0);
        assertNotNull(message.getAttachments());
        assertEquals(2, message.getAttachments().size());
        assertEquals("FALLBACK1", message.getAttachments().get(0).getFallback());
        assertEquals("FALLBACK2", message.getAttachments().get(1).getFallback());
    }

    // Test multiple files in a single message
    @Test
    public void testConversationsHistory_multipleFiles() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"messages\": [" + //
                "        {" + //
                "            \"user\": \"USER\"," + //
                "            \"text\": \"TEXT\"," + //
                "            \"ts\": \"1234567890.000100\"," + //
                "            \"files\": [" + //
                "                {" + //
                "                    \"id\": \"FILE_ID1\"" + //
                "                }," + //
                "                {" + //
                "                    \"id\": \"FILE_ID2\"" + //
                "                }" + //
                "            ]" + //
                "        }" + //
                "    ]," + //
                "    \"has_more\": false" + //
                "}";
        final ConversationsHistoryResponse response =
                new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class);
        assertTrue(response.ok());
        final List<Message> messages = response.getMessages();
        assertEquals(1, messages.size());
        final Message message = messages.get(0);
        assertNotNull(message.getFiles());
        assertEquals(2, message.getFiles().size());
        assertEquals("FILE_ID1", message.getFiles().get(0).getId());
        assertEquals("FILE_ID2", message.getFiles().get(1).getId());
    }

    // Test ConversationsRepliesResponse
    @Test
    public void testConversationsReplies() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"messages\": [" + //
                "        {" + //
                "            \"user\": \"PARENT_USER\"," + //
                "            \"text\": \"PARENT_TEXT\"," + //
                "            \"ts\": \"1234567890.000100\"," + //
                "            \"thread_ts\": \"1234567890.000100\"" + //
                "        }," + //
                "        {" + //
                "            \"user\": \"REPLY_USER\"," + //
                "            \"text\": \"REPLY_TEXT\"," + //
                "            \"ts\": \"1234567891.000200\"," + //
                "            \"thread_ts\": \"1234567890.000100\"" + //
                "        }" + //
                "    ]," + //
                "    \"has_more\": false," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"\"" + //
                "    }" + //
                "}";
        final ConversationsRepliesResponse response =
                new ConversationsRepliesRequest(null, null, null).parseResponse(content, ConversationsRepliesResponse.class);
        assertTrue(response.ok());
        final List<Message> messages = response.getMessages();
        assertEquals(2, messages.size());
        assertEquals("PARENT_USER", messages.get(0).getUser());
        assertEquals("PARENT_TEXT", messages.get(0).getText());
        assertEquals("REPLY_USER", messages.get(1).getUser());
        assertEquals("REPLY_TEXT", messages.get(1).getText());
        assertFalse(response.hasMore());
    }

    // Test pagination
    @Test
    public void testConversationsList_withPagination() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"channels\": [" + //
                "        {" + //
                "            \"id\": \"CHANNEL_ID0\"," + //
                "            \"name\": \"CHANNEL_Name0\"" + //
                "        }" + //
                "    ]," + //
                "    \"response_metadata\": {" + //
                "        \"next_cursor\": \"dGVhbTpDMDYxRkE1UEI=\"" + //
                "    }" + //
                "}";
        final ConversationsListResponse response =
                new ConversationsListRequest(null).parseResponse(content, ConversationsListResponse.class);
        assertTrue(response.ok());
        final List<Channel> channels = response.getChannels();
        assertEquals(1, channels.size());
        assertNotNull(response.getResponseMetadata().getNextCursor());
        assertFalse(response.getResponseMetadata().getNextCursor().isEmpty());
        assertEquals("dGVhbTpDMDYxRkE1UEI=", response.getResponseMetadata().getNextCursor());
    }

    // Test FilesListResponse with paging
    @Test
    public void testFilesList_withPaging() {
        final String content = "" + //
                "{" + //
                "    \"ok\": true," + //
                "    \"files\": [" + //
                "        {" + //
                "            \"id\": \"FILE_ID0\"," + //
                "            \"timestamp\": 1234567890," + //
                "            \"thumb_360\": \"THUMBNAIL0\"" + //
                "        }" + //
                "    ]," + //
                "    \"paging\": {" + //
                "        \"count\": 1," + //
                "        \"page\": 1," + //
                "        \"pages\": 5," + //
                "        \"total\": 5" + //
                "    }" + //
                "}";
        final FilesListResponse response = new FilesListRequest(null).parseResponse(content, FilesListResponse.class);
        assertTrue(response.ok());
        final List<File> files = response.getFiles();
        assertEquals(1, files.size());
        assertEquals(Integer.valueOf(1), response.getPaging().getCount());
        assertEquals(Integer.valueOf(1), response.getPaging().getPage());
        assertEquals(Integer.valueOf(5), response.getPaging().getPages());
        assertEquals(Integer.valueOf(5), response.getPaging().getTotal());
    }

}
