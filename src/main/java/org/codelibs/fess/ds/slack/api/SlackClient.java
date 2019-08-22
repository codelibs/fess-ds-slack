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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.method.bots.BotsClient;
import org.codelibs.fess.ds.slack.api.method.chat.ChatClient;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsClient;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsListResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsRepliesResponse;
import org.codelibs.fess.ds.slack.api.method.files.FilesClient;
import org.codelibs.fess.ds.slack.api.method.team.TeamClient;
import org.codelibs.fess.ds.slack.api.method.users.UsersClient;
import org.codelibs.fess.ds.slack.api.method.users.UsersListResponse;
import org.codelibs.fess.ds.slack.api.type.*;
import org.codelibs.fess.exception.DataStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackClient {

    private static final Logger logger = LoggerFactory.getLogger(SlackClient.class);

    protected static final String SLACK_API_ENDPOINT = "https://slack.com/api/";

    protected static final String TOKEN_PARAM = "token";
    protected static final String CHANNELS_PARAM = "channels";
    protected static final String CHANNELS_ALL = "*all";
    protected static final String CHANNELS_SEPARATOR = ",";

    protected static final String USER_CACHE_SIZE = "user_cache_size";
    protected static final String BOT_CACHE_SIZE = "bot_cache_size";
    protected static final String CHANNEL_CACHE_SIZE = "channel_cache_size";

    protected static final Integer DEFAULT_CHANNEL_FETCH_LIMIT = 100;
    protected static final Integer DEFAULT_USER_FETCH_LIMIT = 100;
    protected static final Integer DEFAULT_MESSAGE_FETCH_LIMIT = 100;
    protected static final String DEFAULT_CACHE_SIZE = "10000";

    protected final String token;

    protected final ConversationsClient conversations;
    protected final UsersClient users;
    protected final FilesClient files;
    protected final BotsClient bots;
    protected final ChatClient chat;
    protected final TeamClient team;
    protected Map<String, String> params;
    protected LoadingCache<String, User> usersCache;
    protected LoadingCache<String, Bot> botsCache;
    protected LoadingCache<String, Channel> channelsCache;

    public SlackClient(final Map<String, String> params) {
        final String token = getToken(params);
        if (token.isEmpty()) {
            throw new DataStoreException("Parameter " + TOKEN_PARAM + " is required");
        }

        this.params = params;
        this.token = token;
        this.conversations = new ConversationsClient(this);
        this.users = new UsersClient(this);
        this.files = new FilesClient(this);
        this.bots = new BotsClient(this);
        this.chat = new ChatClient(this);
        this.team = new TeamClient(this);

        usersCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(params.getOrDefault(USER_CACHE_SIZE, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, User>() {
                        @Override
                        public User load(final String key) {
                            return users.info(key).execute().getUser();
                        }
                    }
                );
        botsCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(params.getOrDefault(BOT_CACHE_SIZE, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Bot>() {
                           @Override
                           public Bot load(final String key) {
                               return bots.info().bot(key).execute().getBot();
                           }
                       }
                );
        channelsCache = CacheBuilder
                .newBuilder()
                .maximumSize(Integer.parseInt(params.getOrDefault(CHANNEL_CACHE_SIZE, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Channel>() {
                           @Override
                           public Channel load(final String key) {
                               return conversations.info(key).execute().getChannel();
                           }
                       }
                );
        getUsers( user -> {
            usersCache.put(user.getId(), user);
            usersCache.put(user.getName(), user);
        });
        getAllChannels( channel -> {
            channelsCache.put(channel.getId(), channel);
            channelsCache.put(channel.getName(), channel);
        });
    }

    protected String getToken(final Map<String, String> paramMap) {
        if (paramMap.containsKey(TOKEN_PARAM)) {
            return paramMap.get(TOKEN_PARAM);
        }
        return StringUtil.EMPTY;
    }

    public Team getTeam() {
        return team.info().execute().getTeam();
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
        return chat.getPermalink(channelId, threadTs).execute().getPermalink();
    }

    public void getChannels(final Consumer<Channel> consumer) {
        if (!params.containsKey(CHANNELS_PARAM) || params.get(CHANNELS_PARAM).equals(CHANNELS_ALL)) {
            getAllChannels(consumer);
        } else {
            for (final String name : params.get(CHANNELS_PARAM).split(CHANNELS_SEPARATOR)) {
                try {
                    consumer.accept(getChannel(name));
                } catch (final Exception e) {
                    logger.warn("Failed to get a channel.", e);
                }
            }
        }
    }

    public void getAllChannels(final Consumer<Channel> consumer) {
        getAllChannels(DEFAULT_CHANNEL_FETCH_LIMIT, consumer);
    }

    public void getAllChannels(final Integer limit, final Consumer<Channel> consumer) {
        ConversationsListResponse response = conversations.list().limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.list\": " + response.getError());
                return;
            }
            response.getChannels().forEach(consumer);
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = conversations.list().limit(limit).cursor(nextCursor).execute();
        }
    }

    public void getChannelMessages(final String channelId, final Consumer<Message> consumer) {
        getChannelMessages(channelId, DEFAULT_MESSAGE_FETCH_LIMIT, consumer);
    }

    public void getChannelMessages(final String channelId, final Integer limit, final Consumer<Message> consumer) {
        ConversationsHistoryResponse response = conversations.history(channelId).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.history\": " + response.getError());
                return;
            }
            response.getMessages().forEach(consumer);
            if (!response.hasMore()) {
                break;
            }
            response = conversations.history(channelId).limit(limit).cursor(response.getResponseMetadata().getNextCursor())
                    .execute();
        }
    }

    public void getMessageReplies(final String channelId, final String threadTs, final Consumer<Message> consumer) {
        getMessageReplies(channelId, threadTs, DEFAULT_MESSAGE_FETCH_LIMIT, consumer);
    }

    public void getMessageReplies(final String channelId, final String threadTs, final Integer limit, final Consumer<Message> consumer) {
        ConversationsRepliesResponse response = conversations.replies(channelId, threadTs).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"conversations.replies\": " + response.getError());
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
            response = conversations.replies(channelId, threadTs).limit(limit)
                    .cursor(response.getResponseMetadata().getNextCursor()).execute();
        }
    }

    public void getUsers(final Consumer<User> consumer) {
        getUsers(DEFAULT_USER_FETCH_LIMIT, consumer);
    }

    public void getUsers(final Integer limit, final Consumer<User> consumer) {
        UsersListResponse response = users.list().limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                logger.warn("Slack API error occured on \"users.list\": " + response.getError());
                return;
            }
            response.getMembers().forEach(consumer);
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = users.list().limit(limit).cursor(nextCursor).execute();
        }
    }

    public CurlRequest request(final Function<String, CurlRequest> method, final String path) {
        final StringBuilder buf = new StringBuilder(100);
        buf.append(SLACK_API_ENDPOINT);
        if (path != null) {
            buf.append(path);
        }
        return method.apply(buf.toString()).header("Authorization", "Bearer " + token);
    }
}
