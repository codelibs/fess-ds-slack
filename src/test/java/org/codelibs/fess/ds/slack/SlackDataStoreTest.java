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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Bot;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Comment;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Profile;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.mylasta.direction.FessConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.dbflute.utflute.lastaflute.LastaFluteTestCase;

public class SlackDataStoreTest extends LastaFluteTestCase {

    private static Logger logger = LogManager.getLogger(SlackDataStoreTest.class);

    public SlackDataStore dataStore;

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
        dataStore = new SlackDataStore();
    }

    @Override
    public void tearDown() throws Exception {
        ComponentUtil.setFessConfig(null);
        super.tearDown();
    }

    public void test_storeData() {
        // doStoreDataTest();
    }

    protected void doStoreDataTest() {

        final DataConfig dataConfig = new DataConfig();
        final IndexUpdateCallback callback = new IndexUpdateCallback() {
            @Override
            public void store(DataStoreParams paramMap, Map<String, Object> dataMap) {
                logger.info("[{}.{}] dataMap = {}", getClass(), getName(), dataMap);
            }

            @Override
            public long getExecuteTime() {
                return 0;
            }

            @Override
            public long getDocumentSize() {
                return 0;
            }

            @Override
            public void commit() {
            }
        };
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "");
        paramMap.put("channels", "");
        final Map<String, String> scriptMap = new HashMap<>();
        final Map<String, Object> defaultDataMap = new HashMap<>();

        final FessConfig fessConfig = ComponentUtil.getFessConfig();
        scriptMap.put(fessConfig.getIndexFieldTitle(), "message.user + \" #\" + message.channel");
        scriptMap.put(fessConfig.getIndexFieldContent(), "message.text + \"\\n\" + message.attachments");
        scriptMap.put(fessConfig.getIndexFieldCreated(), "message.timestamp");
        scriptMap.put(fessConfig.getIndexFieldUrl(), "message.permalink");

        dataStore.storeData(dataConfig, callback, paramMap, scriptMap, defaultDataMap);

    }

    // Test getMaxFilesize method
    public void test_getMaxFilesize_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    public void test_getMaxFilesize_validValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "5000000");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(5000000L, maxFilesize);
    }

    public void test_getMaxFilesize_invalidValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("max_filesize", "invalid");
        final long maxFilesize = dataStore.getMaxFilesize(paramMap);
        assertEquals(10000000L, maxFilesize);
    }

    // Test isIgnoreError method
    public void test_isIgnoreError_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    public void test_isIgnoreError_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "true");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertTrue(ignoreError);
    }

    public void test_isIgnoreError_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("ignore_error", "false");
        final boolean ignoreError = dataStore.isIgnoreError(paramMap);
        assertFalse(ignoreError);
    }

    // Test isFileCrawl method
    public void test_isFileCrawl_defaultValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    public void test_isFileCrawl_trueValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "true");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertTrue(fileCrawl);
    }

    public void test_isFileCrawl_falseValue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("file_crawl", "false");
        final boolean fileCrawl = dataStore.isFileCrawl(paramMap);
        assertFalse(fileCrawl);
    }

    // Test getMessageText method
    public void test_getMessageText_normalText() {
        final Message message = new Message();
        message.text = "Hello World";
        final String text = dataStore.getMessageText(message);
        assertEquals("Hello World", text);
    }

    public void test_getMessageText_nullText() {
        final Message message = new Message();
        message.text = null;
        final String text = dataStore.getMessageText(message);
        assertEquals("", text);
    }

    // Test getMessageTimestamp method
    public void test_getMessageTimestamp() {
        final Message message = new Message();
        message.ts = "1234567890.123456";
        final Date timestamp = dataStore.getMessageTimestamp(message);
        assertNotNull(timestamp);
        assertEquals(1234567890123L, timestamp.getTime());
    }

    public void test_getMessageTimestamp_integerTimestamp() {
        final Message message = new Message();
        message.ts = "1234567890.000000";
        final Date timestamp = dataStore.getMessageTimestamp(message);
        assertNotNull(timestamp);
        assertEquals(1234567890000L, timestamp.getTime());
    }

    // Test getFileTimestamp method
    public void test_getFileTimestamp() {
        final File file = new File();
        file.timestamp = 1234567890L;
        final Date timestamp = dataStore.getFileTimestamp(file);
        assertNotNull(timestamp);
        assertEquals(1234567890000L, timestamp.getTime());
    }

    // Test getMessageUsername method
    public void test_getMessageUsername_normalUser() throws ExecutionException {
        final Message message = new Message();
        message.user = "U12345";

        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = "Test User";
        user.profile = profile;

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getMessageUsername(mockClient, message);
        assertEquals("Test User", username);
    }

    public void test_getMessageUsername_nullUser() {
        final Message message = new Message();
        message.user = null;

        final SlackClient mockClient = new SlackClient(createMockParams());
        final String username = dataStore.getMessageUsername(mockClient, message);
        assertEquals("", username);
    }

    public void test_getMessageUsername_botMessage() throws ExecutionException {
        final Message message = new Message();
        message.user = null;
        message.subtype = "bot_message";
        message.botId = "B12345";

        final Bot bot = new Bot();
        bot.id = "B12345";
        bot.name = "Test Bot";

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public Bot getBot(final String botName) throws ExecutionException {
                return bot;
            }
        };

        final String username = dataStore.getMessageUsername(mockClient, message);
        assertEquals("Test Bot", username);
    }

    public void test_getMessageUsername_fileComment() throws ExecutionException {
        final Message message = new Message();
        message.user = null;
        message.subtype = "file_comment";

        final Comment comment = new Comment();
        comment.user = "U12345";
        message.comment = comment;

        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = "Comment User";
        profile.realName = "Real Name";
        user.profile = profile;

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getMessageUsername(mockClient, message);
        assertEquals("Comment User", username);
    }

    // Test getFileUsername method
    public void test_getFileUsername() throws ExecutionException {
        final File file = new File();
        file.user = "U12345";

        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = "File Owner";
        user.profile = profile;

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getFileUsername(mockClient, file);
        assertEquals("File Owner", username);
    }

    public void test_getFileUsername_nullUser() {
        final File file = new File();
        file.user = null;

        final SlackClient mockClient = new SlackClient(createMockParams());
        final String username = dataStore.getFileUsername(mockClient, file);
        assertEquals("", username);
    }

    // Test getUsername method with fallback
    public void test_getUsername_displayName() throws ExecutionException {
        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = "Display Name";
        profile.realName = "Real Name";
        user.profile = profile;
        user.name = "username";

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getUsername(mockClient, "U12345");
        assertEquals("Display Name", username);
    }

    public void test_getUsername_realName() throws ExecutionException {
        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = null;
        profile.realName = "Real Name";
        user.profile = profile;
        user.realName = "Real Name";
        user.name = "username";

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getUsername(mockClient, "U12345");
        assertEquals("Real Name", username);
    }

    public void test_getUsername_name() throws ExecutionException {
        final User user = new User();
        user.id = "U12345";
        final Profile profile = new Profile();
        profile.displayName = null;
        profile.realName = null;
        user.profile = profile;
        user.realName = null;
        user.name = "username";

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                return user;
            }
        };

        final String username = dataStore.getUsername(mockClient, "U12345");
        assertEquals("username", username);
    }

    public void test_getUsername_fallbackToUserId() throws ExecutionException {
        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public User getUser(final String userName) throws ExecutionException {
                throw new ExecutionException("User not found", new RuntimeException());
            }
        };

        final String username = dataStore.getUsername(mockClient, "U12345");
        assertEquals("U12345", username);
    }

    // Test getMessageAttachmentsText method
    public void test_getMessageAttachmentsText_nullAttachments() {
        final Message message = new Message();
        message.attachments = null;
        final String attachmentsText = dataStore.getMessageAttachmentsText(message);
        assertEquals("", attachmentsText);
    }

    public void test_getMessageAttachmentsText_emptyAttachments() {
        final Message message = new Message();
        message.attachments = new ArrayList<>();
        final String attachmentsText = dataStore.getMessageAttachmentsText(message);
        assertEquals("", attachmentsText);
    }

    public void test_getMessageAttachmentsText_singleAttachment() {
        final Message message = new Message();
        final List<Attachment> attachments = new ArrayList<>();
        final Attachment attachment = new Attachment();
        attachment.fallback = "Attachment 1";
        attachments.add(attachment);
        message.attachments = attachments;

        final String attachmentsText = dataStore.getMessageAttachmentsText(message);
        assertEquals("Attachment 1", attachmentsText);
    }

    public void test_getMessageAttachmentsText_multipleAttachments() {
        final Message message = new Message();
        final List<Attachment> attachments = new ArrayList<>();

        final Attachment attachment1 = new Attachment();
        attachment1.fallback = "Attachment 1";
        attachments.add(attachment1);

        final Attachment attachment2 = new Attachment();
        attachment2.fallback = "Attachment 2";
        attachments.add(attachment2);

        message.attachments = attachments;

        final String attachmentsText = dataStore.getMessageAttachmentsText(message);
        assertEquals("Attachment 1\nAttachment 2", attachmentsText);
    }

    // Test getMessagePermalink method
    public void test_getMessagePermalink_existingPermalink() {
        final Message message = new Message();
        message.permalink = "https://existing.slack.com/archives/C123/p1234567890";
        message.ts = "1234567890.123456";

        final Channel channel = new Channel();
        channel.id = "C123";

        final Team team = new Team();
        team.domain = "test";

        final SlackClient mockClient = new SlackClient(createMockParams());

        final String permalink = dataStore.getMessagePermalink(mockClient, team, channel, message);
        assertEquals("https://existing.slack.com/archives/C123/p1234567890", permalink);
    }

    public void test_getMessagePermalink_generatedWithTeam() {
        final Message message = new Message();
        message.permalink = null;
        message.ts = "1234567890.123456";

        final Channel channel = new Channel();
        channel.id = "C123";

        final Team team = new Team();
        team.domain = "testteam";

        final SlackClient mockClient = new SlackClient(createMockParams());

        final String permalink = dataStore.getMessagePermalink(mockClient, team, channel, message);
        assertEquals("https://testteam.slack.com/archives/C123/p1234567890123456", permalink);
    }

    public void test_getMessagePermalink_generatedWithClient() {
        final Message message = new Message();
        message.permalink = null;
        message.ts = "1234567890.123456";

        final Channel channel = new Channel();
        channel.id = "C123";

        final SlackClient mockClient = new SlackClient(createMockParams()) {
            @Override
            public String getPermalink(final String channelId, final String threadTs) {
                return "https://clientgenerated.slack.com/archives/" + channelId + "/p" + threadTs.replace(".", "");
            }
        };

        final String permalink = dataStore.getMessagePermalink(mockClient, null, channel, message);
        assertEquals("https://clientgenerated.slack.com/archives/C123/p1234567890123456", permalink);
    }

    // Helper method to create mock parameters
    private DataStoreParams createMockParams() {
        final DataStoreParams params = new DataStoreParams();
        params.put("token", "xoxb-test-token");
        return params;
    }

}
