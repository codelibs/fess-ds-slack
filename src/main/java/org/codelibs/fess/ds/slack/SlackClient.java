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

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.codelibs.fess.entity.DataStoreParams;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Slack Web API client that provides high-level access to Slack data including
 * teams, channels, users, messages, and files. This client manages authentication,
 * caching, and rate limiting for efficient access to the Slack API.
 *
 * <p>Key features:</p>
 * <ul>
 * <li>Authentication with OAuth tokens</li>
 * <li>Caching of users, bots, and channels for performance</li>
 * <li>Support for both public and private channels</li>
 * <li>Pagination handling for large datasets</li>
 * <li>Proxy support for network configurations</li>
 * </ul>
 */
public class SlackClient implements Closeable {

    private static final Logger logger = LogManager.getLogger(SlackClient.class);

    /** Parameter name for the OAuth access token. */
    protected static final String TOKEN_PARAM = "token";
    /** Parameter name for including private channels. */
    protected static final String INCLUDE_PRIVATE_PARAM = "include_private";
    /** Parameter name for specifying channels to crawl. */
    protected static final String CHANNELS_PARAM = "channels";
    /** Special value to indicate all channels should be crawled. */
    protected static final String CHANNELS_ALL = "*all";
    /** Separator for multiple channel names. */
    protected static final String CHANNELS_SEPARATOR = ",";
    /** Parameter name for channel pagination limit. */
    protected static final String CHANNEL_COUNT_PARAM = "channel_count";
    /** Parameter name for user pagination limit. */
    protected static final String USER_COUNT_PARAM = "user_count";
    /** Parameter name for message pagination limit. */
    protected static final String MESSAGE_COUNT_PARAM = "message_count";
    /** Parameter name for file pagination limit. */
    protected static final String FILE_COUNT_PARAM = "file_count";
    /** Parameter name for proxy host configuration. */
    protected static final String PROXY_HOST_PARAM = "proxy_host";
    /** Parameter name for proxy port configuration. */
    protected static final String PROXY_PORT_PARAM = "proxy_port";
    /** Parameter name for file type filtering. */
    protected static final String FILE_TYPES_PARAM = "file_types";

    /** Parameter name for user cache size configuration. */
    protected static final String USER_CACHE_SIZE_PARAM = "user_cache_size";
    /** Parameter name for bot cache size configuration. */
    protected static final String BOT_CACHE_SIZE_PARAM = "bot_cache_size";
    /** Parameter name for channel cache size configuration. */
    protected static final String CHANNEL_CACHE_SIZE_PARAM = "channel_cache_size";

    /** Default pagination limit for channels. */
    protected static final String DEFAULT_CHANNEL_COUNT = "100";
    /** Default pagination limit for users. */
    protected static final String DEFAULT_USER_COUNT = "100";
    /** Default pagination limit for messages. */
    protected static final String DEFAULT_MESSAGE_COUNT = "100";
    /** Default pagination limit for files. */
    protected static final String DEFAULT_FILE_COUNT = "20";
    /** Default cache size for all caches. */
    protected static final String DEFAULT_CACHE_SIZE = "10000";

    /** Whether to include private channels in operations. */
    protected final Boolean includePrivate;
    /** Authentication credentials for Slack API access. */
    protected final Authentication authentication;
    /** Configuration parameters for the data store. */
    protected DataStoreParams paramMap;
    /** Cache for user information to improve performance. */
    protected LoadingCache<String, User> usersCache;
    /** Cache for bot information to improve performance. */
    protected LoadingCache<String, Bot> botsCache;
    /** Cache for channel information to improve performance. */
    protected LoadingCache<String, Channel> channelsCache;

    /**
     * Creates a new Slack client with the specified configuration parameters.
     * Initializes authentication, proxy settings, and caches for improved performance.
     *
     * @param paramMap the configuration parameters including token, proxy settings, and cache sizes
     * @throws SlackDataStoreException if required parameters are missing or invalid
     */
    public SlackClient(final DataStoreParams paramMap) {
        final String token = getToken(paramMap);

        if (token.isEmpty()) {
            throw new SlackDataStoreException("Parameter " + TOKEN_PARAM + " required");
        }

        this.paramMap = paramMap;
        includePrivate = isIncludePrivate(paramMap);

        authentication = new Authentication(token);

        final String httpProxyHost = getProxyHost(paramMap);
        final String httpProxyPort = getProxyPort(paramMap);
        if (!httpProxyHost.isEmpty()) {
            if (httpProxyPort.isEmpty()) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' required.");
            }
            try {
                authentication.setHttpProxy(httpProxyHost, Integer.parseInt(httpProxyPort));
            } catch (final NumberFormatException e) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' invalid.", e);
            }
        }

        usersCache = CacheBuilder.newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getAsString(USER_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, User>() {
                    @Override
                    public User load(final String key) {
                        return usersInfo(key).execute().getUser();
                    }
                });
        botsCache = CacheBuilder.newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getAsString(BOT_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Bot>() {
                    @Override
                    public Bot load(final String key) {
                        return botsInfo().bot(key).execute().getBot();
                    }
                });
        channelsCache = CacheBuilder.newBuilder()
                .maximumSize(Integer.parseInt(paramMap.getAsString(CHANNEL_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Channel>() {
                    @Override
                    public Channel load(final String key) {
                        return conversationsInfo(key).execute().getChannel();
                    }
                });
        // Initialize caches to avoid exceeding the rate limit of the Slack API
        getUsers(user -> {
            usersCache.put(user.getId(), user);
            usersCache.put(user.getName(), user);
        });
        getAllChannels(channel -> {
            channelsCache.put(channel.getId(), channel);
            channelsCache.put(channel.getName(), channel);
        });
    }

    /**
     * Creates a bots.info API request builder.
     *
     * @return a new BotsInfoRequest instance
     */
    public BotsInfoRequest botsInfo() {
        return new BotsInfoRequest(authentication);
    }

    /**
     * Creates a chat.getPermalink API request builder.
     *
     * @param channel the channel ID or name
     * @param ts the message timestamp
     * @return a new ChatGetPermalinkRequest instance
     */
    public ChatGetPermalinkRequest chatGetPermalink(final String channel, final String ts) {
        return new ChatGetPermalinkRequest(authentication, channel, ts);
    }

    /**
     * Creates a conversations.list API request builder.
     *
     * @return a new ConversationsListRequest instance
     */
    public ConversationsListRequest conversationsList() {
        return new ConversationsListRequest(authentication);
    }

    /**
     * Creates a conversations.history API request builder.
     *
     * @param channel the channel ID or name
     * @return a new ConversationsHistoryRequest instance
     */
    public ConversationsHistoryRequest conversationsHistory(final String channel) {
        return new ConversationsHistoryRequest(authentication, channel);
    }

    /**
     * Creates a conversations.info API request builder.
     *
     * @param channel the channel ID or name
     * @return a new ConversationsInfoRequest instance
     */
    public ConversationsInfoRequest conversationsInfo(final String channel) {
        return new ConversationsInfoRequest(authentication, channel);
    }

    /**
     * Creates a conversations.replies API request builder.
     *
     * @param channel the channel ID or name
     * @param ts the message timestamp
     * @return a new ConversationsRepliesRequest instance
     */
    public ConversationsRepliesRequest conversationsReplies(final String channel, final String ts) {
        return new ConversationsRepliesRequest(authentication, channel, ts);
    }

    /**
     * Creates a files.list API request builder.
     *
     * @return a new FilesListRequest instance
     */
    public FilesListRequest filesList() {
        return new FilesListRequest(authentication);
    }

    /**
     * Creates a files.info API request builder.
     *
     * @param file the file ID
     * @return a new FilesInfoRequest instance
     */
    public FilesInfoRequest filesInfo(final String file) {
        return new FilesInfoRequest(authentication, file);
    }

    /**
     * Creates a team.info API request builder.
     *
     * @return a new TeamInfoRequest instance
     */
    public TeamInfoRequest teamInfo() {
        return new TeamInfoRequest(authentication);
    }

    /**
     * Creates a users.list API request builder.
     *
     * @return a new UsersListRequest instance
     */
    public UsersListRequest usersList() {
        return new UsersListRequest(authentication);
    }

    /**
     * Creates a users.info API request builder.
     *
     * @param user the user ID or username
     * @return a new UsersInfoRequest instance
     */
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

    /**
     * Extracts the OAuth access token from the configuration parameters.
     *
     * @param paramMap the configuration parameters
     * @return the OAuth access token or empty string if not found
     */
    protected String getToken(final DataStoreParams paramMap) {
        return paramMap.getAsString(TOKEN_PARAM, StringUtil.EMPTY);
    }

    /**
     * Determines whether private channels should be included in operations.
     *
     * @param paramMap the configuration parameters
     * @return true if private channels should be included, false otherwise
     */
    protected Boolean isIncludePrivate(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(INCLUDE_PRIVATE_PARAM, Constants.FALSE));
    }

    /**
     * Extracts the proxy host from the configuration parameters.
     *
     * @param paramMap the configuration parameters
     * @return the proxy host or empty string if not configured
     */
    protected String getProxyHost(final DataStoreParams paramMap) {
        return paramMap.getAsString(PROXY_HOST_PARAM, StringUtil.EMPTY);
    }

    /**
     * Extracts the proxy port from the configuration parameters.
     *
     * @param paramMap the configuration parameters
     * @return the proxy port or empty string if not configured
     */
    protected String getProxyPort(final DataStoreParams paramMap) {
        return paramMap.getAsString(PROXY_PORT_PARAM, StringUtil.EMPTY);
    }

    /**
     * Returns the channel types to include based on configuration.
     *
     * @return comma-separated list of channel types to include
     */
    protected String getTypes() {
        return includePrivate ? "public_channel,private_channel" : "public_channel";
    }

    /**
     * Returns the file types to include when crawling files.
     *
     * @return comma-separated list of file types or "all" for all types
     */
    protected String getFileTypes() {
        return paramMap.getAsString(FILE_TYPES_PARAM, "all");
    }

    /**
     * Retrieves information about the current team.
     *
     * @return the team information
     */
    public Team getTeam() {
        return teamInfo().execute().getTeam();
    }

    /**
     * Retrieves bot information by bot name, using cache for performance.
     *
     * @param botName the bot name or ID
     * @return the bot information
     * @throws ExecutionException if the bot information cannot be retrieved
     */
    public Bot getBot(final String botName) throws ExecutionException {
        return botsCache.get(botName);
    }

    /**
     * Retrieves user information by username, using cache for performance.
     *
     * @param userName the username or user ID
     * @return the user information
     * @throws ExecutionException if the user information cannot be retrieved
     */
    public User getUser(final String userName) throws ExecutionException {
        return usersCache.get(userName);
    }

    /**
     * Retrieves channel information by channel name, using cache for performance.
     *
     * @param channelName the channel name or ID
     * @return the channel information
     * @throws ExecutionException if the channel information cannot be retrieved
     */
    public Channel getChannel(final String channelName) throws ExecutionException {
        return channelsCache.get(channelName);
    }

    /**
     * Retrieves the permalink URL for a specific message.
     *
     * @param channelId the channel ID
     * @param threadTs the message timestamp
     * @return the permalink URL for the message
     */
    public String getPermalink(final String channelId, final String threadTs) {
        return chatGetPermalink(channelId, threadTs).execute().getPermalink();
    }

    /**
     * Downloads a file from Slack using authenticated HTTP request.
     *
     * @param fileUrl the URL of the file to download
     * @return the HTTP response containing the file content
     */
    public CurlResponse getFileResponse(final String fileUrl) {
        return Curl.get(fileUrl)
                .header("Authorization", "Bearer " + getToken(paramMap))
                .header("Content-type", "application/x-www-form-urlencoded ")
                .execute();
    }

    /**
     * Processes channels based on configuration, either all channels or specific ones.
     *
     * @param consumer the function to process each channel
     */
    public void getChannels(final Consumer<Channel> consumer) {
        if (!paramMap.containsKey(CHANNELS_PARAM) || CHANNELS_ALL.equals(paramMap.get(CHANNELS_PARAM))) {
            getAllChannels(consumer);
        } else {
            for (final String name : paramMap.getAsString(CHANNELS_PARAM, StringUtil.EMPTY).split(CHANNELS_SEPARATOR)) {
                try {
                    consumer.accept(getChannel(name));
                } catch (final ExecutionException e) {
                    logger.warn("Failed to get a channel.", e);
                }
            }
        }
    }

    /**
     * Retrieves all files from a specific channel using default pagination.
     *
     * @param channelId the channel ID
     * @param consumer the function to process each file
     */
    public void getChannelFiles(final String channelId, final Consumer<File> consumer) {
        getChannelFiles(channelId, Integer.parseInt(paramMap.getAsString(FILE_COUNT_PARAM, DEFAULT_FILE_COUNT)), consumer);
    }

    /**
     * Retrieves files from a specific channel with custom pagination.
     *
     * @param channelId the channel ID
     * @param count the number of files to retrieve per page
     * @param consumer the function to process each file
     */
    public void getChannelFiles(final String channelId, final Integer count, final Consumer<File> consumer) {
        FilesListResponse response = filesList().channel(channelId).types(getFileTypes()).count(count).execute();
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

    /**
     * Retrieves all channels using default pagination.
     *
     * @param consumer the function to process each channel
     */
    public void getAllChannels(final Consumer<Channel> consumer) {
        getAllChannels(Integer.parseInt(paramMap.getAsString(CHANNEL_COUNT_PARAM, DEFAULT_CHANNEL_COUNT)), consumer);
    }

    /**
     * Retrieves all channels with custom pagination limit.
     *
     * @param limit the maximum number of channels to retrieve per page
     * @param consumer the function to process each channel
     */
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

    /**
     * Retrieves all messages from a specific channel using default pagination.
     *
     * @param channelId the channel ID
     * @param consumer the function to process each message
     */
    public void getChannelMessages(final String channelId, final Consumer<Message> consumer) {
        getChannelMessages(channelId, Integer.parseInt(paramMap.getAsString(MESSAGE_COUNT_PARAM, DEFAULT_MESSAGE_COUNT)), consumer);
    }

    /**
     * Retrieves messages from a specific channel with custom pagination limit.
     *
     * @param channelId the channel ID
     * @param limit the maximum number of messages to retrieve per page
     * @param consumer the function to process each message
     */
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
            response = conversationsHistory(channelId).limit(limit).cursor(response.getResponseMetadata().getNextCursor()).execute();
        }
    }

    /**
     * Retrieves all replies to a threaded message using default pagination.
     *
     * @param channelId the channel ID
     * @param threadTs the thread timestamp
     * @param consumer the function to process each reply message
     */
    public void getMessageReplies(final String channelId, final String threadTs, final Consumer<Message> consumer) {
        getMessageReplies(channelId, threadTs, Integer.parseInt(paramMap.getAsString(MESSAGE_COUNT_PARAM, DEFAULT_MESSAGE_COUNT)),
                consumer);
    }

    /**
     * Retrieves replies to a threaded message with custom pagination limit.
     *
     * @param channelId the channel ID
     * @param threadTs the thread timestamp
     * @param limit the maximum number of replies to retrieve per page
     * @param consumer the function to process each reply message
     */
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
            response =
                    conversationsReplies(channelId, threadTs).limit(limit).cursor(response.getResponseMetadata().getNextCursor()).execute();
        }
    }

    /**
     * Retrieves all users using default pagination.
     *
     * @param consumer the function to process each user
     */
    public void getUsers(final Consumer<User> consumer) {
        getUsers(Integer.parseInt(paramMap.getAsString(USER_COUNT_PARAM, DEFAULT_USER_COUNT)), consumer);
    }

    /**
     * Retrieves all users with custom pagination limit.
     *
     * @param limit the maximum number of users to retrieve per page
     * @param consumer the function to process each user
     */
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
