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
package org.codelibs.fess.ds.slack;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.SlackClient;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesResponse;
import org.codelibs.fess.ds.slack.api.method.users.UsersListResponse;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Bot;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.es.config.exentity.DataConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackDataStore extends AbstractDataStore {

    private static final Logger logger = LoggerFactory.getLogger(SlackDataStore.class);

    // parameters
    protected static final String TOKEN_PARAM = "token";
    protected static final String CHANNELS_PARAM = "channels";
    protected static final String CHANNELS_ALL = "*all";
    protected static final String CHANNELS_SEPARATOR = ",";

    // scripts
    protected static final String MESSAGE = "message";
    protected static final String MESSAGE_TEXT = "text";
    protected static final String MESSAGE_TIMESTAMP = "timestamp";
    protected static final String MESSAGE_USER = "user";
    protected static final String MESSAGE_CHANNEL = "channel";
    protected static final String MESSAGE_PERMALINK = "permalink";
    protected static final String MESSAGE_ATTACHMENTS = "attachments";

    protected final Map<String, User> usersMap = new HashMap<>();
    protected final Map<String, Bot> botsMap = new HashMap<>();
    protected final Map<String, Channel> channelsMap = new HashMap<>();

    protected String getName() {
        return "Slack";
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final String token = getToken(paramMap);
        if (token.isEmpty()) {
            logger.warn("parameter \"" + TOKEN_PARAM + "\" is required");
            return;
        }

        final SlackClient client = new SlackClient(token);
        final Team team = client.team.info().execute().getTeam();
        initUsersMap(client);
        initChannelsMap(client);
        storeMessages(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team);
    }

    protected void initUsersMap(final SlackClient client) {
        UsersListResponse response = client.users.list().limit(100).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"users.list\": " + response.getError());
                return;
            }
            for (final User user : response.getMembers()) {
                usersMap.put(user.getId(), user);
                usersMap.put(user.getName(), user);
            }
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = client.users.list().limit(100).cursor(nextCursor).execute();
        }
    }

    protected void initChannelsMap(final SlackClient client) {
        ConversationsListResponse response = client.conversations.list().limit(100).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.list\": " + response.getError());
                return;
            }
            for (final Channel channel : response.getChannels()) {
                channelsMap.put(channel.getId(), channel);
                channelsMap.put(channel.getName(), channel);
            }
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = client.conversations.list().limit(100).cursor(nextCursor).execute();
        }
    }

    protected void storeMessages(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final SlackClient client, final Team team) {
        for (final Channel channel : getChannels(paramMap)) {
            processChannelMessages(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel);
        }
    }

    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel) {
        ConversationsHistoryResponse response = client.conversations.history(channel.getId()).limit(1000).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.history\": " + response.getError());
                return;
            }
            response.getMessages().forEach(message -> {
                processMessage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                if (message.getThreadTs() != null) {
                    processMessageReplies(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                }
            });
            if (!response.hasMore()) {
                break;
            }
            response = client.conversations.history(channel.getId()).limit(1000).cursor(response.getResponseMetadata().getNextCursor())
                    .execute();
        }
    }

    protected void processMessageReplies(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final Message message) {
        ConversationsRepliesResponse response = client.conversations.replies(channel.getId(), message.getThreadTs()).limit(1000).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.replies\": " + response.getError());
                return;
            }
            final List<Message> messages = response.getMessages();
            for (int i = 1; i < messages.size(); i++) {
                if (message.isThreadBroadcast()) {
                    continue;
                }
                processMessage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, messages.get(i));
            }
            if (!response.hasMore()) {
                break;
            }
            response = client.conversations.replies(channel.getId(), message.getThreadTs()).limit(1000)
                    .cursor(response.getResponseMetadata().getNextCursor()).execute();
        }
    }

    protected void processMessage(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final SlackClient client, final Team team,
            final Channel channel, final Message message) {
        final Map<String, Object> dataMap = new HashMap<>();
        dataMap.putAll(defaultDataMap);
        final Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.putAll(paramMap);
        final Map<String, Object> messageMap = new HashMap<>();

        try {
            messageMap.put(MESSAGE_TEXT, getMessageText(client, message));
            messageMap.put(MESSAGE_TIMESTAMP, getMessageTimestamp(message));
            messageMap.put(MESSAGE_USER, getMessageUser(client, message));
            messageMap.put(MESSAGE_CHANNEL, channel.getName());
            messageMap.put(MESSAGE_PERMALINK, getMessagePermalink(client, team, channel, message));
            messageMap.put(MESSAGE_ATTACHMENTS, getMessageAttachmentsText(message));
            resultMap.put(MESSAGE, messageMap);

            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }
            callback.store(paramMap, dataMap);
        } catch (final CrawlingAccessException e) {
            logger.warn("Crawling Access Exception at : " + dataMap, e);
        }
    }

    protected String getMessageText(final SlackClient client, final Message message) {
        final String text = message.getText();
        return text != null ? text : "";
    }

    protected Date getMessageTimestamp(final Message message) {
        return new Date(Math.round(Double.parseDouble(message.getTs()) * 1000));
    }

    protected String getMessageUser(final SlackClient client, final Message message) {
        if (message.getUsername() != null) {
            return message.getUsername();
        }
        if (message.getSubtype() != null) {
            if (message.getSubtype().equals("bot_message")) {
                Bot bot = botsMap.get(message.getBotId());
                if (bot == null) {
                    botsMap.put(message.getBotId(), bot = client.bots.info().bot(message.getBotId()).execute().getBot());
                }
                return bot.getName();
            } else if (message.getSubtype().equals("file_comment")) {
                User user = usersMap.get(message.getComment().getUser());
                if (user == null) {
                    usersMap.put(message.getUser(), user = client.users.info(message.getComment().getUser()).execute().getUser());
                }
                return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName() : user.getProfile().getRealName();
            }
        }
        User user = usersMap.get(message.getUser());
        if (user == null) {
            usersMap.put(message.getUser(), user = client.users.info(message.getUser()).execute().getUser());
        }
        return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName() : user.getProfile().getRealName();
    }

    protected String getMessagePermalink(final SlackClient client, final Team team, final Channel channel, final Message message) {
        String permalink = message.getPermalink();
        if (permalink == null) {
            if (team == null) {
                permalink = client.chat.getPermalink(channel.getId(), message.getTs()).execute().getPermalink();
            } else {
                permalink =
                        "https://" + team.getDomain() + ".slack.com/archives/" + channel.getId() + "/p" + message.getTs().replace(".", "");
            }
        }
        return permalink;
    }

    protected String getMessageAttachmentsText(final Message message) {
        final List<Attachment> attachments = message.getAttachments();
        if (attachments == null) {
            return "";
        }
        final List<String> fallbacks = attachments.stream().map(attachment -> attachment.getFallback()).collect(Collectors.toList());
        return String.join("\n", fallbacks);
    }

    protected String getToken(final Map<String, String> paramMap) {
        if (paramMap.containsKey(TOKEN_PARAM)) {
            return paramMap.get(TOKEN_PARAM);
        }
        return StringUtil.EMPTY;
    }

    protected List<Channel> getChannels(final Map<String, String> paramMap) {
        final List<Channel> channels = new ArrayList<>();
        if (!paramMap.containsKey(CHANNELS_PARAM) || paramMap.get(CHANNELS_PARAM).equals(CHANNELS_ALL)) {
            channels.addAll(channelsMap.values());
        } else {
            for (final String name : paramMap.get(CHANNELS_PARAM).split(CHANNELS_SEPARATOR)) {
                if (channelsMap.containsKey(name)) {
                    channels.add(channelsMap.get(name));
                }
            }
        }
        return channels;
    }

}
