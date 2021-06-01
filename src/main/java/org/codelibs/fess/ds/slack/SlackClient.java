/*
 * Copyright 2012-2021 CodeLibs Project and the Others.
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

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.Constants;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.method.bots.BotsInfoRequest;
import org.codelibs.fess.ds.slack.api.method.chat.ChatGetPermalinkRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsInfoRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesResponse;
import org.codelibs.fess.ds.slack.api.method.files.FilesInfoRequest;
import org.codelibs.fess.ds.slack.api.method.files.FilesListRequest;
import org.codelibs.fess.ds.slack.api.method.files.FilesListResponse;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoRequest;
import org.codelibs.fess.ds.slack.api.method.users.UsersInfoRequest;
import org.codelibs.fess.ds.slack.api.method.users.UsersListRequest;
import org.codelibs.fess.ds.slack.api.method.users.UsersListResponse;
import org.codelibs.fess.ds.slack.api.type.Bot;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackClient implements Closeable  {

    private static final Logger logger = LoggerFactory.getLogger(SlackClient.class);

    protected static final String TOKEN_PARAM = "token";
    protected static final String INCLUDE_PRIVATE_PARAM = "include_private";
    protected static final String CHANNELS_PARAM = "channels";
    protected static final String CHANNELS_ALL = "*all";
    protected static final String CHANNELS_SEPARATOR = ",";
    protected static final String CHANNEL_COUNT_PARAM = "channel_count";
    protected static final String USER_COUNT_PARAM = "user_count";
    protected static final String MESSAGE_COUNT_PARAM = "message_count";
    protected static final String FILE_COUNT_PARAM = "file_count";
    protected static final String PROXY_HOST_PARAM = "proxy_host";
    protected static final String PROXY_PORT_PARAM = "proxy_port";

    protected static final String USER_CACHE_SIZE_PARAM = "user_cache_size";
    protected static final String BOT_CACHE_SIZE_PARAM = "bot_cache_size";
    protected static final String CHANNEL_CACHE_SIZE_PARAM = "channel_cache_size";

    protected static final String DEFAULT_CHANNEL_COUNT = "100";
    protected static final String DEFAULT_USER_COUNT = "100";
    protected static final String DEFAULT_MESSAGE_COUNT = "100";
    protected static final String DEFAULT_FILE_COUNT = "20";
    protected static final String DEFAULT_CACHE_SIZE = "10000";

    protected final Boolean includePrivate;
    protected final Authentication authentication;
    protected Map<String, String> paramMap;
    protected LoadingCache<String, User> usersCache;
    protected LoadingCache<String, Bot> botsCache;
    protected LoadingCache<String, Channel> channelsCache;

    public SlackClient(final Map<String, String> paramMap) {
        final String token = getToken(paramMap);

        if (token.isEmpty()) {
            throw new SlackDataStoreException("Parameter " + TOKEN_PARAM + " required");
        }

        this.paramMap = paramMap;
        includePrivate = isIncludePrivate(paramMap);

        authentication = new Authentication(token);

        final String httpProxyHost = getProxyHost(paramMap);
        final String httpProxyPort = getProxyPort(paramMap);
        if (!httpProxyHost.isEmpty() ) {
            if (httpProxyPort.isEmpty()) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' required.");
            }
            try {
                authentication.setHttpProxy(httpProxyHost, Integer.parseInt(httpProxyPort));
            } catch (final NumberFormatException e) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' invalid.", e);
            }
        }

        usersCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getOrDefault(USER_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, User>() {
                           @Override
                           public User load(final String key) {
                               return usersInfo(key).execute().getUser();
                           }
                       }
                );
        botsCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getOrDefault(BOT_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Bot>() {
                           @Override
                           public Bot load(final String key) {
                               return botsInfo().bot(key).execute().getBot();
                           }
                       }
                );
        channelsCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getOrDefault(CHANNEL_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Channel>() {
                           @Override
                           public Channel load(final String key) {
                               return conversationsInfo(key).execute().getChannel();
                           }
                       }
                );
        // Initialize caches to avoid exceeding the rate limit of the Slack API
        getUsers( user -> {
            usersCache.put(user.getId(), user);
            usersCache.put(user.getName(), user);
        });
        getAllChannels( channel -> {
            channelsCache.put(channel.getId(), channel);
            channelsCache.put(channel.getName(), channel);
        });
    }

    public BotsInfoRequest botsInfo() {
        return new BotsInfoRequest(authentication);
    }

    public ChatGetPermalinkRequest chatGetPermalink(final String channel, final String ts) {
        return new ChatGetPermalinkRequest(authentication, channel, ts);
    }


    public ConversationsListRequest conversationsList() {
        return new ConversationsListRequest(authentication);
    }

    public ConversationsHistoryRequest conversationsHistory(final String channel) {
        return new ConversationsHistoryRequest(authentication, channel);
    }

    public ConversationsInfoRequest conversationsInfo(final String channel) {
        return new ConversationsInfoRequest(authentication, channel);
    }

    public ConversationsRepliesRequest conversationsReplies(final String channel, final String ts) {
        return new ConversationsRepliesRequest(authentication, channel, ts);
    }

    public FilesListRequest filesList() {
        return new FilesListRequest(authentication);
    }

    public FilesInfoRequest filesInfo(final String file) {
        return new FilesInfoRequest(authentication, file);
    }

    public TeamInfoRequest teamInfo() {
        return new TeamInfoRequest(authentication);
    }

    public UsersListRequest usersList() {
        return new UsersListRequest(authentication);
    }

    public UsersInfoRequest usersInfo(final String user) {
        return new UsersInfoRequest(authentication, user);
    }

    @Override
    public void close() {
        // TODO
        usersCache.invalidateAll();
        botsCache.invalidateAll();
        channelsCache.invalidateAll();
    }

    protected String getToken(final Map<String, String> paramMap) {
        if (paramMap.containsKey(TOKEN_PARAM)) {
            return paramMap.get(TOKEN_PARAM);
        }
        return StringUtil.EMPTY;
    }

    protected Boolean isIncludePrivate(final Map<String, String> paramMap) {
        return paramMap.getOrDefault(INCLUDE_PRIVATE_PARAM, Constants.FALSE).equalsIgnoreCase(Constants.TRUE);
    }

    protected String getProxyHost(final Map<String, String> paramMap) {
        if (paramMap.containsKey(PROXY_HOST_PARAM)) {
            return paramMap.get(PROXY_HOST_PARAM);
        }
        return StringUtil.EMPTY;
    }

    protected String getProxyPort(final Map<String, String> paramMap) {
        if (paramMap.containsKey(PROXY_PORT_PARAM)) {
            return paramMap.get(PROXY_PORT_PARAM);
        }
        return StringUtil.EMPTY;
    }

    protected String getTypes() {
        return includePrivate ? "public_channel,private_channel" : "public_channel";
    }

    public Team getTeam() {
        return teamInfo().execute().getTeam();
    }

    public Bot getBot(final String botName) throws ExecutionException {
        return botsCache.get(botName);
    }

    public User getUser(final String userName) throws ExecutionException {
        return usersCache.get(userName);
    }

    public Channel getChannel(final String channelName) throws ExecutionException {
        return channelsCache.get(channelName);
    }

    public String getPermalink(final String channelId, final String threadTs) {
        return chatGetPermalink(channelId, threadTs).execute().getPermalink();
    }

    public CurlResponse getFileResponse(final String fileUrl) {
        return Curl.get(fileUrl).header("Authorization", "Bearer " + getToken(paramMap))
                .header("Content-type", "application/x-www-form-urlencoded ")
                .execute();
    }

    public void getChannels(final Consumer<Channel> consumer) {
        if (!paramMap.containsKey(CHANNELS_PARAM) || paramMap.get(CHANNELS_PARAM).equals(CHANNELS_ALL)) {
            getAllChannels(consumer);
        } else {
            for (final String name : paramMap.get(CHANNELS_PARAM).split(CHANNELS_SEPARATOR)) {
                try {
                    consumer.accept(getChannel(name));
                } catch (final ExecutionException e) {
                    logger.warn("Failed to get a channel.", e);
                }
            }
        }
    }

    public void getChannelFiles(final String channelId, final Consumer<File> consumer) {
        getChannelFiles(channelId, Integer.parseInt(paramMap.getOrDefault(FILE_COUNT_PARAM, DEFAULT_FILE_COUNT)), consumer);
    }

    public void getChannelFiles(final String channelId, final Integer count, final Consumer<File> consumer) {
        FilesListResponse response = filesList().channel(channelId).types(getTypes()).count(count).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"files.list\": {}", response.responseBody());
                return;
            }
            response.getFiles().forEach(consumer);
            if (response.getPaging().getPage() >= response.getPaging().getTotal()) {
                break;
            }
            response = filesList().channel(channelId).count(count).page(response.getPaging().getPage() + 1).execute();
        }
    }

    public void getAllChannels(final Consumer<Channel> consumer) {
        getAllChannels(Integer.parseInt(paramMap.getOrDefault(CHANNEL_COUNT_PARAM, DEFAULT_CHANNEL_COUNT)), consumer);
    }

    public void getAllChannels(final Integer limit, final Consumer<Channel> consumer) {
        ConversationsListResponse response = conversationsList().types(getTypes()).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.list\": {}", response.responseBody());
                return;
            }
            response.getChannels().forEach(consumer);
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = conversationsList().types(getTypes()).limit(limit).cursor(nextCursor).execute();
        }
    }

    public void getChannelMessages(final String channelId, final Consumer<Message> consumer) {
        getChannelMessages(channelId, Integer.parseInt(paramMap.getOrDefault(MESSAGE_COUNT_PARAM, DEFAULT_MESSAGE_COUNT)), consumer);
    }

    public void getChannelMessages(final String channelId, final Integer limit, final Consumer<Message> consumer) {
        ConversationsHistoryResponse response = conversationsHistory(channelId).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.history\": {}", response.responseBody());
                return;
            }
            response.getMessages().forEach(consumer);
            if (!response.hasMore()) {
                break;
            }
            response = conversationsHistory(channelId).limit(limit).cursor(response.getResponseMetadata().getNextCursor())
                    .execute();
        }
    }

    public void getMessageReplies(final String channelId, final String threadTs, final Consumer<Message> consumer) {
        getMessageReplies(channelId, threadTs, Integer.parseInt(paramMap.getOrDefault(MESSAGE_COUNT_PARAM, DEFAULT_MESSAGE_COUNT)), consumer);
    }

    public void getMessageReplies(final String channelId, final String threadTs, final Integer limit, final Consumer<Message> consumer) {
        ConversationsRepliesResponse response = conversationsReplies(channelId, threadTs).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.replies\": {}", response.responseBody());
                return;
            }
            final List<Message> messages = response.getMessages();
            for (int i = 1; i < messages.size(); i++) {
                final Message message = messages.get(i);
                if (message.isThreadBroadcast()) {
                    continue;
                }
                consumer.accept(message);
            }
            if (!response.hasMore()) {
                break;
            }
            response = conversationsReplies(channelId, threadTs).limit(limit)
                    .cursor(response.getResponseMetadata().getNextCursor()).execute();
        }
    }

    public void getUsers(final Consumer<User> consumer) {
        getUsers(Integer.parseInt(paramMap.getOrDefault(USER_COUNT_PARAM, DEFAULT_USER_COUNT)), consumer);
    }

    public void getUsers(final Integer limit, final Consumer<User> consumer) {
        UsersListResponse response = usersList().limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"users.list\": {}", response.responseBody());
                return;
            }
            response.getMembers().forEach(consumer);
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = usersList().limit(limit).cursor(nextCursor).execute();
        }
    }

}
