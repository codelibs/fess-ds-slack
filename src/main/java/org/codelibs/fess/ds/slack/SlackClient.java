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
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlRequest;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.Constants;
import org.codelibs.fess.ds.slack.api.RequestContext;
import org.codelibs.fess.ds.slack.api.Response;
import org.codelibs.fess.ds.slack.api.SlackApiException;
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
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
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
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * Slack Web API client that provides high-level access to Slack data including
 * teams, channels, users, messages, and files. This client manages the request context,
 * caching, and rate limiting for efficient access to the Slack API.
 *
 * <p>Key features:</p>
 * <ul>
 * <li>Request context carrying OAuth tokens and proxy settings</li>
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
    /** Parameter name for excluding archived channels from {@code conversations.list}. */
    protected static final String EXCLUDE_ARCHIVED_PARAM = "exclude_archived";
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
    /** Parameter name for connection timeout configuration. */
    protected static final String CONNECTION_TIMEOUT_PARAM = "connection_timeout";
    /** Parameter name for read timeout configuration. */
    protected static final String READ_TIMEOUT_PARAM = "read_timeout";
    /** Parameter name for the maximum number of retries on a 429/5xx response. */
    protected static final String MAX_RETRY_COUNT_PARAM = "max_retry_count";
    /** Parameter name for the wait, in milliseconds, before the first retry. */
    protected static final String RETRY_INTERVAL_PARAM = "retry_interval";
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
    // Connection/read timeout and retry defaults live on RequestContext, not here: SlackClient's
    // constructor unconditionally calls setTimeouts/setRetry, so a copy declared on this class
    // would be dead in production and could drift from what RequestContext itself falls back to.
    // See RequestContext.DEFAULT_CONNECTION_TIMEOUT's javadoc.

    /**
     * Slack error codes that mean the token itself cannot be used, so every later call would
     * fail the same way. {@link #handleApiError} throws {@link SlackApiException} for these
     * instead of warning and skipping, so the crawl is reported as failed rather than as a
     * false "success" with zero or partial documents.
     *
     * <p>
     * {@code not_allowed_token_type} is included even though it is absent from the
     * authentication-specific list the other five come from: Slack documents it as "the token
     * type used in this request is not allowed", which is a property of the credential rather
     * than of the channel or page being fetched, so it recurs identically on every subsequent
     * call. Left in the warn-and-skip catch-all it produced a crawl that walked zero channels
     * and still reported success. The rest of the boilerplate error table Slack stamps onto
     * every method page is deliberately <em>not</em> promoted -- codes such as
     * {@code invalid_cursor} and {@code access_denied} are request-scoped, and treating them as
     * fatal would abort crawls that should merely skip.
     * </p>
     */
    protected static final Set<String> FATAL_ERROR_CODES = Set.of("invalid_auth", "token_revoked", "account_inactive", "missing_scope",
            "not_authed", "token_expired", "not_allowed_token_type");

    /**
     * Slack error codes that are transient/server-side rather than a property of the specific
     * channel or page being fetched, so {@link #handleApiError} fails the crawl for these the
     * same way it does for {@link #FATAL_ERROR_CODES}, instead of warning and skipping as it
     * does for a channel-scoped code such as {@code channel_not_found}.
     *
     * <p>
     * {@code ratelimited} is Slack's 429 body ({@code {"ok":false,"error":"ratelimited"}});
     * {@code internal_error}, {@code service_unavailable}, and {@code request_timeout} are
     * documented 5xx-shaped failures; {@code fatal_error} is documented as also arising from an
     * over-large page size on a 200 (see {@link org.codelibs.fess.ds.slack.api.Request#isRetryableStatus}),
     * which is why it belongs here and not in {@link #FATAL_ERROR_CODES}: it is not a statement
     * about the token. None of these five says anything about whether the specific channel or
     * page being walked exists or is accessible -- unlike {@code channel_not_found} or {@code
     * not_in_channel} -- so skipping just that one channel/page would under-index silently
     * instead of surfacing a condition that likely affects every other call just as much.
     * </p>
     */
    protected static final Set<String> TRANSIENT_ERROR_CODES =
            Set.of("ratelimited", "internal_error", "fatal_error", "service_unavailable", "request_timeout");

    /**
     * Synthetic error code used in a {@link SlackApiException} raised because
     * {@link Response#retriesExhausted()} was set but the exhausted response carried no {@code
     * error} of its own -- for example a 5xx with an unparseable or empty body. Never returned by
     * Slack itself.
     */
    protected static final String RETRIES_EXHAUSTED_ERROR_CODE = "retries_exhausted";

    /**
     * The one Slack error code that is an expected, non-error outcome rather than a failure:
     * {@code channel_join}/{@code channel_leave} messages cannot be threaded, so calling
     * conversations.replies on their {@code ts} always returns this. {@link #handleApiError}
     * logs it at debug, not warn, so it does not spam every crawl.
     */
    protected static final String THREAD_NOT_FOUND_ERROR_CODE = "thread_not_found";

    /** Whether to include private channels in operations. */
    protected final Boolean includePrivate;
    /** Whether {@code conversations.list} should exclude archived channels. */
    protected final Boolean excludeArchived;
    /** Request context for Slack API access. */
    protected final RequestContext requestContext;
    /** Configuration parameters for the data store. */
    protected DataStoreParams paramMap;
    /** Cache for user information to improve performance. */
    protected LoadingCache<String, User> usersCache;
    /** Cache for bot information to improve performance. */
    protected LoadingCache<String, Bot> botsCache;
    /** Cache for channel information to improve performance. */
    protected LoadingCache<String, Channel> channelsCache;
    /** Channels captured during the constructor preload, in listing order. */
    protected final List<Channel> preloadedChannels = new ArrayList<>();
    /**
     * Reports whether the crawl should keep paging. Consulted at each iteration of every
     * paging loop in this class -- the constructor's {@code users.list}/{@code
     * conversations.list} preload included -- so an operator stopping the crawl from the admin
     * UI (which flips {@link org.codelibs.fess.ds.AbstractDataStore#alive} to {@code false})
     * takes effect at the next page boundary instead of only after every page of every channel
     * has been walked.
     */
    protected final BooleanSupplier aliveSupplier;

    /**
     * Creates a new Slack client with the specified configuration parameters.
     * Initializes the request context, proxy settings, and caches for improved performance.
     *
     * <p>
     * Equivalent to {@link #SlackClient(DataStoreParams, BooleanSupplier)} with a supplier that
     * always returns {@code true}, so this client's paging never stops early. Kept as a separate
     * overload because the test suite constructs {@link SlackClient} directly far more often than
     * it needs to test stopping.
     * </p>
     *
     * @param paramMap the configuration parameters including token, proxy settings, and cache sizes
     * @throws SlackDataStoreException if required parameters are missing or invalid
     */
    public SlackClient(final DataStoreParams paramMap) {
        this(paramMap, () -> true);
    }

    /**
     * Creates a new Slack client with the specified configuration parameters and an explicit
     * {@code aliveSupplier}. Initializes the request context, proxy settings, and caches for
     * improved performance.
     *
     * @param paramMap the configuration parameters including token, proxy settings, and cache sizes
     * @param aliveSupplier reports whether the crawl should keep paging; see {@link #aliveSupplier}
     * @throws SlackDataStoreException if required parameters are missing or invalid
     */
    public SlackClient(final DataStoreParams paramMap, final BooleanSupplier aliveSupplier) {
        this.aliveSupplier = aliveSupplier;
        final String token = getToken(paramMap);

        if (token.isEmpty()) {
            throw new SlackDataStoreException("Parameter " + TOKEN_PARAM + " required");
        }

        this.paramMap = paramMap;
        includePrivate = isIncludePrivate(paramMap);
        excludeArchived = isExcludeArchived(paramMap);

        requestContext = new RequestContext(token);

        final String httpProxyHost = getProxyHost(paramMap);
        final String httpProxyPort = getProxyPort(paramMap);
        if (!httpProxyHost.isEmpty()) {
            if (httpProxyPort.isEmpty()) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' required.");
            }
            try {
                requestContext.setHttpProxy(httpProxyHost, Integer.parseInt(httpProxyPort));
            } catch (final NumberFormatException e) {
                throw new SlackDataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' invalid.", e);
            }
        }

        requestContext.setTimeouts(getConnectionTimeout(paramMap), getReadTimeout(paramMap));
        requestContext.setRetry(getMaxRetryCount(paramMap), getRetryInterval(paramMap));

        usersCache = CacheBuilder.newBuilder()
                // Each user is cached under both its ID and its name (see the preload
                // below), so the effective capacity is half the configured size unless
                // we double it here.
                .maximumSize(Long.parseLong(paramMap.getAsString(USER_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)) * 2L)
                .build(new CacheLoader<String, User>() {
                    @Override
                    public User load(final String key) {
                        return usersInfo(key).execute().getUser();
                    }
                });
        botsCache = CacheBuilder.newBuilder()
                // Bots are cached only under the key they were looked up by, so this
                // cache is not subject to the double-keying that users and channels are.
                .maximumSize(Integer.parseInt(paramMap.getAsString(BOT_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)))
                .build(new CacheLoader<String, Bot>() {
                    @Override
                    public Bot load(final String key) {
                        return botsInfo().bot(key).execute().getBot();
                    }
                });
        channelsCache = CacheBuilder.newBuilder()
                // Each channel is cached under both its ID and its name (see the preload
                // below), so the effective capacity is half the configured size unless
                // we double it here.
                .maximumSize(Long.parseLong(paramMap.getAsString(CHANNEL_CACHE_SIZE_PARAM, DEFAULT_CACHE_SIZE)) * 2L)
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
            preloadedChannels.add(channel);
        });
    }

    /**
     * Creates a bots.info API request builder.
     *
     * @return a new BotsInfoRequest instance
     */
    public BotsInfoRequest botsInfo() {
        return new BotsInfoRequest(requestContext);
    }

    /**
     * Creates a chat.getPermalink API request builder.
     *
     * @param channel the channel ID or name
     * @param ts the message timestamp
     * @return a new ChatGetPermalinkRequest instance
     */
    public ChatGetPermalinkRequest chatGetPermalink(final String channel, final String ts) {
        return new ChatGetPermalinkRequest(requestContext, channel, ts);
    }

    /**
     * Creates a conversations.list API request builder.
     *
     * @return a new ConversationsListRequest instance
     */
    public ConversationsListRequest conversationsList() {
        return new ConversationsListRequest(requestContext);
    }

    /**
     * Creates a conversations.history API request builder.
     *
     * @param channel the channel ID or name
     * @return a new ConversationsHistoryRequest instance
     */
    public ConversationsHistoryRequest conversationsHistory(final String channel) {
        return new ConversationsHistoryRequest(requestContext, channel);
    }

    /**
     * Creates a conversations.info API request builder.
     *
     * @param channel the channel ID or name
     * @return a new ConversationsInfoRequest instance
     */
    public ConversationsInfoRequest conversationsInfo(final String channel) {
        return new ConversationsInfoRequest(requestContext, channel);
    }

    /**
     * Creates a conversations.replies API request builder.
     *
     * @param channel the channel ID or name
     * @param ts the message timestamp
     * @return a new ConversationsRepliesRequest instance
     */
    public ConversationsRepliesRequest conversationsReplies(final String channel, final String ts) {
        return new ConversationsRepliesRequest(requestContext, channel, ts);
    }

    /**
     * Creates a files.list API request builder.
     *
     * @return a new FilesListRequest instance
     */
    public FilesListRequest filesList() {
        return new FilesListRequest(requestContext);
    }

    /**
     * Creates a files.info API request builder.
     *
     * @param file the file ID
     * @return a new FilesInfoRequest instance
     */
    public FilesInfoRequest filesInfo(final String file) {
        return new FilesInfoRequest(requestContext, file);
    }

    /**
     * Creates a team.info API request builder.
     *
     * @return a new TeamInfoRequest instance
     */
    public TeamInfoRequest teamInfo() {
        return new TeamInfoRequest(requestContext);
    }

    /**
     * Creates a users.list API request builder.
     *
     * @return a new UsersListRequest instance
     */
    public UsersListRequest usersList() {
        return new UsersListRequest(requestContext);
    }

    /**
     * Creates a users.info API request builder.
     *
     * @param user the user ID or username
     * @return a new UsersInfoRequest instance
     */
    public UsersInfoRequest usersInfo(final String user) {
        return new UsersInfoRequest(requestContext, user);
    }

    /**
     * Releases this client's in-memory state.
     *
     * <p>
     * That is: the three lookup caches ({@link #usersCache}, {@link #botsCache}, {@link
     * #channelsCache}) and {@link #preloadedChannels}, the channel listing captured by the
     * constructor's {@code conversations.list} preload. There is nothing else here to release --
     * {@link #requestContext} holds only plain configuration values (token, timeouts, retry
     * policy, proxy), not an open connection or a resource of its own, and every Slack API call
     * this class makes opens and closes its own {@link org.codelibs.curl.CurlResponse} at the
     * call site rather than holding one open on this client.
     * </p>
     */
    @Override
    public void close() {
        usersCache.invalidateAll();
        botsCache.invalidateAll();
        channelsCache.invalidateAll();
        preloadedChannels.clear();
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
     * Determines whether {@code conversations.list} should exclude archived channels.
     *
     * <p>
     * Defaults to {@code false} -- Slack's own default -- so an unset parameter leaves current
     * behaviour (archived channels included) unchanged.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return true if archived channels should be excluded, false otherwise
     */
    protected Boolean isExcludeArchived(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(EXCLUDE_ARCHIVED_PARAM, Constants.FALSE));
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
     * Parses an integer configuration parameter, falling back to a default with a warning
     * instead of failing the crawl when the value is not a number.
     *
     * <p>
     * Shared by {@link #getConnectionTimeout}, {@link #getReadTimeout}, and
     * {@link #getMaxRetryCount}: all three follow the same "non-numeric falls back to the
     * default, with a warning" contract, so the parse/catch/warn logic lives here once.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @param paramName the parameter name to read
     * @param defaultValue the value to fall back to
     * @return the parsed value, or {@code defaultValue} if unset or not a number
     */
    protected int getIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final String value = paramMap.getAsString(paramName);
        try {
            return StringUtil.isNotBlank(value) ? Integer.parseInt(value) : defaultValue;
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Parses a non-negative integer configuration parameter via {@link #getIntParam}, then falls
     * back to the default (with its own warning) when the parsed value is negative.
     *
     * <p>
     * Shared by {@link #getConnectionTimeout} and {@link #getReadTimeout}: curl4j treats a
     * negative timeout as "unset" and reverts to the JDK's blocking-forever default, silently
     * restoring the stall these two parameters exist to prevent, and it does not throw the way a
     * negative {@code retry_interval} does when it reaches {@code Thread.sleep}. {@code
     * max_retry_count} does not go through this: a negative retry count merely degrades to
     * "never retries", not to an unbounded block, so it stays on the plain {@link #getIntParam}.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @param paramName the parameter name to read
     * @param defaultValue the value to fall back to
     * @return the parsed value, or {@code defaultValue} if unset, not a number, or negative
     */
    protected int getNonNegativeIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final int value = getIntParam(paramMap, paramName, defaultValue);
        if (value < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    /**
     * Extracts the connection timeout from the configuration parameters.
     *
     * <p>
     * A non-numeric or negative value falls back to {@link RequestContext#DEFAULT_CONNECTION_TIMEOUT}
     * with a warning rather than failing the crawl or silently blocking forever.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the connection timeout in milliseconds
     */
    protected int getConnectionTimeout(final DataStoreParams paramMap) {
        return getNonNegativeIntParam(paramMap, CONNECTION_TIMEOUT_PARAM, RequestContext.DEFAULT_CONNECTION_TIMEOUT);
    }

    /**
     * Extracts the read timeout from the configuration parameters.
     *
     * <p>
     * A non-numeric or negative value falls back to {@link RequestContext#DEFAULT_READ_TIMEOUT}
     * with a warning rather than failing the crawl or silently blocking forever.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the read timeout in milliseconds
     */
    protected int getReadTimeout(final DataStoreParams paramMap) {
        return getNonNegativeIntParam(paramMap, READ_TIMEOUT_PARAM, RequestContext.DEFAULT_READ_TIMEOUT);
    }

    /**
     * Extracts the maximum retry count from the configuration parameters.
     *
     * <p>
     * A non-numeric value falls back to {@link RequestContext#DEFAULT_MAX_RETRY_COUNT} with a
     * warning rather than failing the crawl.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the maximum number of retries for a retryable (429/5xx) response
     */
    protected int getMaxRetryCount(final DataStoreParams paramMap) {
        return getIntParam(paramMap, MAX_RETRY_COUNT_PARAM, RequestContext.DEFAULT_MAX_RETRY_COUNT);
    }

    /**
     * Extracts the retry interval from the configuration parameters.
     *
     * <p>
     * A non-numeric value falls back to {@link RequestContext#DEFAULT_RETRY_INTERVAL} with a
     * warning rather than failing the crawl. A numeric but negative value is just as unusable --
     * it would otherwise reach {@code Thread.sleep} and throw {@link IllegalArgumentException},
     * which is the same "bad config kills the crawl" failure mode -- so it also falls back to the
     * default, with its own warning.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the wait, in milliseconds, before the first retry when no {@code Retry-After}
     *         header is present
     */
    protected long getRetryInterval(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(RETRY_INTERVAL_PARAM);
        final long parsed;
        try {
            parsed = StringUtil.isNotBlank(value) ? Long.parseLong(value) : RequestContext.DEFAULT_RETRY_INTERVAL;
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", RETRY_INTERVAL_PARAM, value,
                    RequestContext.DEFAULT_RETRY_INTERVAL);
            return RequestContext.DEFAULT_RETRY_INTERVAL;
        }
        if (parsed < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", RETRY_INTERVAL_PARAM, parsed,
                    RequestContext.DEFAULT_RETRY_INTERVAL);
            return RequestContext.DEFAULT_RETRY_INTERVAL;
        }
        return parsed;
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
     * Classifies a failed ({@code ok: false}) Slack API response and reacts accordingly. Used by
     * every one of this class's paginated calls (files.list, conversations.list,
     * conversations.history, conversations.replies, users.list) so the "what does this error
     * code mean" decision lives in one place instead of five copies of the same switch.
     *
     * <p>
     * Four outcomes, in order:
     * </p>
     * <ul>
     * <li>A code in {@link #FATAL_ERROR_CODES} (the token can no longer authenticate) throws
     * {@link SlackApiException}, so the caller's paging loop aborts and the crawl is reported as
     * failed instead of a false "success".</li>
     * <li>A code in {@link #TRANSIENT_ERROR_CODES}, or a response whose
     * {@link Response#retriesExhausted()} is set regardless of its error code, also throws
     * {@link SlackApiException}. Both are treated as fatal for the same reason: neither is a
     * property of the specific channel or page being fetched, so skipping just that one
     * channel/page -- the treatment channel-scoped codes get below -- would silently under-index
     * instead of surfacing a condition that almost certainly affects every other call just as
     * much. This is what closes the defect this phase exists to eliminate: a rate limit or a
     * server error that survives every retry used to reach this method as an ordinary {@code
     * ok:false} body with no rule of its own, fall into the catch-all below, and be warned and
     * skipped -- silently truncating the channel walk while the job still reported success.</li>
     * <li>{@link #THREAD_NOT_FOUND_ERROR_CODE} is logged at debug only: it is the expected
     * outcome of calling conversations.replies on a {@code channel_join}/{@code channel_leave}
     * message's {@code ts}, not a failure, and warning on it would spam every crawl.</li>
     * <li>Everything else -- channel-scoped codes such as {@code channel_not_found} and
     * {@code not_in_channel}, and any code this method does not otherwise recognize -- is
     * logged at warn (the error code only; the raw body moves to debug) and left for the caller
     * to skip, typically by returning from the paging loop for just that channel or page.
     * Slack's error tables differ per method -- {@code not_in_channel} is documented for
     * conversations.history but not for conversations.replies -- so an unrecognized code is more
     * likely a gap in that table than a credential problem, and must never be treated as
     * fatal.</li>
     * </ul>
     *
     * @param method the Slack Web API method name, e.g. {@code "conversations.history"}, used
     *            only for logging and the {@link SlackApiException} message
     * @param response the failed response
     * @throws SlackApiException if the error code is one of {@link #FATAL_ERROR_CODES} or
     *             {@link #TRANSIENT_ERROR_CODES}, or if {@link Response#retriesExhausted()} is set
     */
    protected void handleApiError(final String method, final Response response) {
        final String errorCode = response.getError();
        if (errorCode != null && FATAL_ERROR_CODES.contains(errorCode)) {
            throw new SlackApiException(method, errorCode);
        }
        if (response.retriesExhausted() || (errorCode != null && TRANSIENT_ERROR_CODES.contains(errorCode))) {
            logger.warn("Slack API \"{}\" failed with a transient error after {}: {}", method,
                    response.retriesExhausted() ? "exhausting its retries" : "no retry (arrived on HTTP 200)", errorCode);
            throw new SlackApiException(method, errorCode != null ? errorCode : RETRIES_EXHAUSTED_ERROR_CODE);
        }
        if (THREAD_NOT_FOUND_ERROR_CODE.equals(errorCode)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Slack API \"{}\" returned \"{}\" (expected for channel_join/channel_leave messages): {}", method, errorCode,
                        response.responseBody());
            }
            return;
        }
        logger.warn("Slack API error on \"{}\": {}", method, errorCode);
        if (logger.isDebugEnabled()) {
            logger.debug("Slack API error body for \"{}\": {}", method, response.responseBody());
        }
    }

    /**
     * Retrieves information about the current team.
     *
     * <p>
     * A {@code null} return makes callers fall back to resolving each
     * message's permalink via a dedicated {@code chat.getPermalink} call
     * instead of building the URL locally from the team domain, so a
     * failure here is logged rather than left silent.
     * </p>
     *
     * <p>
     * <b>Deliberate exception to {@link #handleApiError}'s table (Ruling-P2-2):</b> every error
     * here, including {@code missing_scope}, is warn-and-continue, not routed through
     * {@link #handleApiError} and never fatal, even though {@code missing_scope} is fatal for
     * every other call in this class. A {@code null} team only degrades permalink quality --
     * callers fall back to a {@code chat.getPermalink} call per message, exactly as this method's
     * warning already tells the operator -- so failing the whole crawl over team.info
     * specifically would be disproportionate. This is intentional, not an oversight.
     * </p>
     *
     * @return the team information, or {@code null} if the call failed
     */
    public Team getTeam() {
        final TeamInfoResponse response = teamInfo().execute();
        if (!response.ok()) {
            logger.warn("Failed to get the team info: {}. Permalinks will be resolved per message via chat.getPermalink, "
                    + "which multiplies API calls. Check that the token has the team:read scope.", response.getError());
            return null;
        }
        return response.getTeam();
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
     * <p>
     * Honours the configured HTTP proxy and connection/read timeouts,
     * matching the API request path in
     * {@link org.codelibs.fess.ds.slack.api.Request#getCurlRequest}: without
     * the proxy, every download attempted a direct connection in a proxied
     * environment and failed, and with {@code ignore_error} defaulting to
     * true the file was indexed with empty content instead. Without the
     * timeouts, a file download that stalls mid-transfer would block a
     * crawler thread indefinitely, same as an unbounded API call.
     * </p>
     *
     * @param fileUrl the URL of the file to download
     * @return the HTTP response containing the file content
     */
    public CurlResponse getFileResponse(final String fileUrl) {
        final CurlRequest request = Curl.get(fileUrl).header("Authorization", "Bearer " + getToken(paramMap));
        final Proxy httpProxy = requestContext.getHttpProxy();
        if (httpProxy != null) {
            request.proxy(httpProxy);
        }
        request.timeout(requestContext.getConnectionTimeout(), requestContext.getReadTimeout());
        return request.execute();
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
            for (final String rawName : paramMap.getAsString(CHANNELS_PARAM, StringUtil.EMPTY).split(CHANNELS_SEPARATOR)) {
                final String name = rawName.trim();
                if (StringUtil.isBlank(name)) {
                    continue;
                }
                try {
                    final Channel channel = getChannel(name);
                    if (channel != null) {
                        consumer.accept(channel);
                    } else {
                        logger.warn("Channel not found: {}", name);
                    }
                } catch (final ExecutionException | UncheckedExecutionException | InvalidCacheLoadException e) {
                    logger.warn("Failed to get a channel: {}", name, e);
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
        int page = 1;
        while (true) {
            final FilesListResponse response = filesList().channel(channelId).types(getFileTypes()).count(count).page(page).execute();
            if (!response.ok()) {
                handleApiError("files.list", response);
                return;
            }
            response.getFiles().forEach(consumer);
            if (!aliveSupplier.getAsBoolean()) {
                return;
            }
            final FilesListResponse.Paging paging = response.getPaging();
            if (paging == null || paging.getPages() == null) {
                // Without paging info there is no way to know whether more pages remain, so
                // stop here rather than loop forever. Files beyond this page are silently
                // under-indexed; warn so that is visible instead of looking like a complete
                // channel.
                logger.warn("\"files.list\" response for channel {} on page {} carries no paging info; "
                        + "any files beyond this page were not indexed.", channelId, page);
                break;
            }
            if (page >= paging.getPages().intValue()) {
                break;
            }
            page++;
        }
    }

    /**
     * Retrieves all channels using default pagination.
     *
     * <p>
     * The constructor preload already walks the full channel list once to
     * warm {@link #channelsCache}; this reuses that listing instead of
     * walking {@code conversations.list} a second time. {@code
     * conversations.list} is a Tier 2 method, and re-walking it here made it
     * the first thing every crawl exhausted its rate limit on.
     * </p>
     *
     * @param consumer the function to process each channel
     */
    public void getAllChannels(final Consumer<Channel> consumer) {
        if (!preloadedChannels.isEmpty()) {
            preloadedChannels.forEach(consumer);
            return;
        }
        getAllChannels(Integer.parseInt(paramMap.getAsString(CHANNEL_COUNT_PARAM, DEFAULT_CHANNEL_COUNT)), consumer);
    }

    /**
     * Retrieves all channels with custom pagination limit.
     *
     * @param limit the maximum number of channels to retrieve per page
     * @param consumer the function to process each channel
     */
    public void getAllChannels(final Integer limit, final Consumer<Channel> consumer) {
        ConversationsListResponse response = conversationsList().types(getTypes()).excludeArchived(excludeArchived).limit(limit).execute();
        while (true) {
            if (!response.ok()) {
                handleApiError("conversations.list", response);
                return;
            }
            response.getChannels().forEach(consumer);
            if (!aliveSupplier.getAsBoolean()) {
                return;
            }
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = conversationsList().types(getTypes()).excludeArchived(excludeArchived).limit(limit).cursor(nextCursor).execute();
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
                handleApiError("conversations.history", response);
                return;
            }
            response.getMessages().forEach(consumer);
            if (!response.hasMore()) {
                break;
            }
            if (!aliveSupplier.getAsBoolean()) {
                return;
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
                handleApiError("conversations.replies", response);
                return;
            }
            final List<Message> messages = response.getMessages();
            for (int i = 1; i < messages.size(); i++) {
                final Message message = messages.get(i);
                // Slack documents thread_broadcast as a message subtype, not as a boolean
                // field: Message#isThreadBroadcast() is always false because the backing
                // field is protected with no setter and Jackson's default field visibility
                // is PUBLIC_ONLY, so none of the plausible payload shapes ever populate it.
                // Without this guard actually firing, a broadcast reply is fetched here a
                // second time -- once via conversations.history, once via this walk -- and
                // only avoids being duplicated because both resolve the same permalink and
                // the second store overwrites the first.
                if ("thread_broadcast".equals(message.getSubtype())) {
                    continue;
                }
                consumer.accept(message);
            }
            if (!response.hasMore()) {
                break;
            }
            if (!aliveSupplier.getAsBoolean()) {
                return;
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
                handleApiError("users.list", response);
                return;
            }
            response.getMembers().forEach(consumer);
            if (!aliveSupplier.getAsBoolean()) {
                return;
            }
            final String nextCursor = response.getResponseMetadata().getNextCursor();
            if (nextCursor.isEmpty()) {
                break;
            }
            response = usersList().limit(limit).cursor(nextCursor).execute();
        }
    }

}
