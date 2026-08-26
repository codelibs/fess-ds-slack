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

import static java.util.Collections.EMPTY_LIST;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MaxLengthExceededException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Profile;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.lastaflute.di.core.exception.ComponentNotFoundException;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * Slack Data Store implementation that enables Fess to crawl and index Slack content
 * including channels, messages, and files. This data store connects to the Slack API
 * to retrieve content and makes it searchable within Fess.
 *
 * <p>Supported content types:</p>
 * <ul>
 * <li>Channel messages with threading support</li>
 * <li>File attachments with content extraction</li>
 * <li>Bot messages and user posts</li>
 * <li>Public and private channels (configurable)</li>
 * </ul>
 *
 * <p>Configuration parameters:</p>
 * <ul>
 * <li>token: OAuth access token for Slack API</li>
 * <li>channels: Specific channels to crawl or "*all" for all channels</li>
 * <li>include_private: Whether to include private channels</li>
 * <li>file_crawl: Whether to crawl file attachments</li>
 * <li>number_of_threads: Thread pool size for parallel processing</li>
 * </ul>
 */
public class SlackDataStore extends AbstractDataStore {

    private static final Logger logger = LogManager.getLogger(SlackDataStore.class);

    /**
     * Default constructor for SlackDataStore.
     */
    public SlackDataStore() {
        super();
    }

    /** Default maximum file size for processing (10MB). */
    protected static final long DEFAULT_MAX_FILESIZE = 10000000L; // 10m

    // parameters
    /** Parameter name for ignoring errors during crawling. */
    protected static final String IGNORE_ERROR = "ignore_error";
    /** Parameter name for supported MIME types. */
    protected static final String SUPPORTED_MIMETYPES = "supported_mimetypes";
    /** Parameter name for URL include patterns. */
    protected static final String INCLUDE_PATTERN = "include_pattern";
    /** Parameter name for URL exclude patterns. */
    protected static final String EXCLUDE_PATTERN = "exclude_pattern";
    /** Parameter name for URL filter configuration. */
    protected static final String URL_FILTER = "url_filter";
    /**
     * {@code configMap} key holding this crawl's stop flag, an {@link AtomicBoolean}.
     *
     * <p>
     * Not a user-settable parameter: {@code storeData} puts it there so the per-message and
     * per-file paths can reach it without adding another argument to six already-long
     * signatures, the same way {@link #URL_FILTER} carries a resolved component rather than a
     * setting.
     * </p>
     */
    protected static final String CRAWL_ALIVE = "crawl_alive";
    /** Parameter name for thread pool size. */
    protected static final String NUMBER_OF_THREADS = "number_of_threads";
    /** Parameter name for maximum file size. */
    protected static final String MAX_FILESIZE = "max_filesize";
    /** Parameter name for enabling file crawling. */
    protected static final String FILE_CRAWL = "file_crawl";
    /** Regular expression pattern that matches any MIME type; the default of {@link #SUPPORTED_MIMETYPES}. */
    protected static final String MATCH_ALL_MIMETYPES = ".*";
    /** Parameter name for how long, in seconds, to wait for queued crawl work to finish before forcing shutdown. */
    protected static final String EXECUTOR_TIMEOUT = "executor_timeout";
    /** Default number of seconds {@link #storeData} waits for queued crawl work to finish before forcing shutdown. */
    protected static final int DEFAULT_EXECUTOR_TIMEOUT = 60;
    /** Parameter name for excluding Slack-generated channel-administration messages. */
    protected static final String IGNORE_SYSTEM_EVENTS = "ignore_system_events";
    /** Parameter name for the delay, in milliseconds, to sleep after each processed message/file. */
    protected static final String READ_INTERVAL = "read_interval";

    /**
     * Message {@code subtype} values that are Slack-generated channel-administration
     * notifications rather than content a person wrote, dropped by default (see
     * {@link #isIgnoreSystemEvents}).
     *
     * <p>
     * Deliberately narrower than every subtype Slack documents at reference/events/message:
     * </p>
     * <ul>
     * <li>{@code file_share} and {@code reply_broadcast} are excluded from this set on purpose --
     * Slack documents both as "no longer served", so a message can never carry them.</li>
     * <li>{@code thread_broadcast} is excluded from this set on purpose -- it is a real message
     * a person wrote, broadcast to the channel in addition to its thread, not a system
     * notification. {@code SlackClient#getMessageReplies} already skips it for an unrelated
     * reason (avoiding double-indexing a reply reachable both from conversations.history and
     * conversations.replies); it must still be indexed once, not zero times.</li>
     * <li>{@code bot_message} and {@code me_message} are excluded from this set on purpose --
     * both carry content a person (or an integration acting on a channel's behalf) chose to
     * post, not a Slack-generated notification.</li>
     * <li>{@code message_changed}/{@code message_deleted}/{@code message_replied} are excluded
     * from this set because they are moot: Slack documents all three as {@code hidden: true} and
     * says they "will not return in calls to conversations.history", so this class never sees
     * them regardless.</li>
     * </ul>
     */
    protected static final Set<String> SYSTEM_EVENT_SUBTYPES = Set.of("channel_join", "channel_leave", "channel_topic", "channel_purpose",
            "channel_name", "channel_archive", "channel_unarchive", "group_join", "group_leave", "group_topic", "group_purpose",
            "group_name", "group_archive", "group_unarchive", "pinned_item", "unpinned_item");

    /**
     * Parameter keys withheld from crawl scripts: credentials and proxy configuration.
     *
     * <p>
     * This is a denylist over an otherwise-complete copy of the parameter map, not an
     * allowlist: any new parameter is exposed to scripts by default. Whoever adds a new
     * credential-shaped parameter (a token, a password, a secret key, ...) must add its key
     * here, or it leaks into every script's data map the same way {@link SlackClient#TOKEN_PARAM}
     * did before this set existed.
     * </p>
     */
    protected static final Set<String> SECRET_PARAMS =
            Set.of(SlackClient.TOKEN_PARAM, SlackClient.PROXY_HOST_PARAM, SlackClient.PROXY_PORT_PARAM);

    // scripts
    /** Script field name for message data. */
    protected static final String MESSAGE = "message";
    /** Script field name for message title. */
    protected static final String MESSAGE_TITLE = "title";
    /** Script field name for message text content. */
    protected static final String MESSAGE_TEXT = "text";
    /** Script field name for team information. */
    protected static final String MESSAGE_TEAM = "team";
    /** Script field name for message timestamp. */
    protected static final String MESSAGE_TIMESTAMP = "timestamp";
    /** Script field name for user information. */
    protected static final String MESSAGE_USER = "user";
    /** Script field name for channel information. */
    protected static final String MESSAGE_CHANNEL = "channel";
    /** Script field name for message permalink. */
    protected static final String MESSAGE_PERMALINK = "permalink";
    /** Script field name for message attachments. */
    protected static final String MESSAGE_ATTACHMENTS = "attachments";

    /** Name of the content extractor to use for file processing. */
    protected String extractorName = "tikaExtractor";

    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Sets the name of the content extractor to use for file processing.
     *
     * @param extractorName the extractor name
     */
    public void setExtractorName(final String extractorName) {
        this.extractorName = extractorName;
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final Map<String, Object> configMap = new HashMap<>();
        configMap.put(MAX_FILESIZE, getMaxFilesize(paramMap));
        configMap.put(IGNORE_ERROR, isIgnoreError(paramMap));
        configMap.put(SUPPORTED_MIMETYPES, getSupportedMimeTypes(paramMap));
        configMap.put(FILE_CRAWL, isFileCrawl(paramMap));
        configMap.put(IGNORE_SYSTEM_EVENTS, isIgnoreSystemEvents(paramMap));
        configMap.put(READ_INTERVAL, getReadInterval(paramMap));
        configMap.put(URL_FILTER, getUrlFilter(paramMap));
        if (logger.isDebugEnabled()) {
            logger.debug("configMap: {}", configMap);
        }

        final ExecutorService executorService = newFixedThreadPool(Integer.parseInt(paramMap.getAsString(NUMBER_OF_THREADS, "1")));
        // A fatal SlackApiException thrown inside a worker thread (conversations.replies, run
        // from processChannelMessages's executorService.execute(...)) does not propagate to this
        // method the way one thrown on this thread does: ThreadPoolExecutor swallows an
        // uncaught RuntimeException from its Runnable via the default uncaught-exception handler
        // instead of re-throwing it to the submitter. Without this latch, storeData would return
        // normally -- reporting success -- after a worker silently ate a fatal error. Only the
        // first one is kept: a later failure on another worker is very likely the same fatal
        // condition recurring, not new information.
        final AtomicReference<SlackApiException> fatalError = new AtomicReference<>();
        // Scoped to this one crawl, deliberately NOT the inherited AbstractDataStore#alive flag.
        // A data store is a LastaDi singleton (see fess_ds++.xml) that DataStoreFactory hands to
        // every crawl for the lifetime of the JVM, and nothing in Fess ever sets `alive` back to
        // true -- it is assigned exactly once, at field initialisation. Calling stop() to abort
        // one crawl would therefore leave every later crawl of every Slack DataConfig walking
        // zero channels and indexing zero documents, silently and permanently. A local
        // AtomicBoolean, rather than a plain local variable, because the abort can be requested
        // from a worker thread (see resolveFailureUrl, reached from processMessage/processFile
        // via executorService) while this method's own channel walk reads it.
        final AtomicBoolean crawlAlive = new AtomicBoolean(true);
        configMap.put(CRAWL_ALIVE, crawlAlive);
        // A supplier over both flags rather than a one-time snapshot, so a stop that lands after
        // this client is constructed is still seen by every paging loop SlackClient runs
        // afterward. The two are different stops and both must be honoured: `alive` is the
        // operator's admin-UI stop button (set by DataIndexHelper on the shared singleton, and
        // never reset), crawlAlive is this crawl's own abort.
        try (final SlackClient client = new SlackClient(paramMap, () -> alive && crawlAlive.get())) {
            final Team team = client.getTeam();
            final boolean fileCrawl = (Boolean) configMap.get(FILE_CRAWL);
            client.getChannels(channel -> {
                // Checked here, not only inside SlackClient's paging loops: this is the loop that
                // decides whether to dispatch the *next* channel at all, so stopping here skips
                // channels that have not been started yet instead of only cutting short the one
                // already in progress.
                if (!alive || !crawlAlive.get()) {
                    return;
                }
                processChannelMessages(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team,
                        channel, fatalError);
                if (fileCrawl) {
                    processChannelFiles(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team,
                            channel, fatalError);
                }
            });

            if (logger.isDebugEnabled()) {
                logger.debug("Shutting down thread executor.");
            }

            final int executorTimeout = getExecutorTimeout(paramMap);
            executorService.shutdown();
            if (!executorService.awaitTermination(executorTimeout, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate within {} seconds ({}={}); interrupting the tasks still running.", executorTimeout,
                        EXECUTOR_TIMEOUT, executorTimeout);
                // The JDK's documented two-phase shutdown, and the reason it matters here:
                // shutdownNow() only *requests* cancellation and returns immediately, so a
                // single wait would let the fatalError.get() below run while a worker is still
                // unwinding -- and a worker that latches a fatal error during that unwind would
                // be lost, leaving a failed crawl to report success. One request can legitimately
                // outlive the first window: max_retry_count retries, each waiting up to a minute
                // or for as long as Slack's Retry-After asks. A second bounded wait narrows that
                // race rather than closing it, which is why the give-up is logged at warn.
                executorService.shutdownNow();
                if (!executorService.awaitTermination(executorTimeout, TimeUnit.SECONDS)) {
                    logger.warn("Executor did not terminate {} seconds after being interrupted either; queued crawl work was "
                            + "discarded, and a failure discovered from here on cannot be reported.", executorTimeout);
                }
            }
        } catch (final InterruptedException e) {
            throw new InterruptedRuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }

        // Checked after the executor has been shut down and awaited above, so this reports the
        // final state of the crawl rather than a state that could still change. Skipped when a
        // fatal error was also latched: that is thrown below and is the more actionable of the
        // two for an operator to see.
        if (!alive && fatalError.get() == null) {
            logger.info("Slack crawl for \"{}\" was stopped before completing; the index may contain fewer documents than a full crawl.",
                    dataConfig.getName());
        }

        // Re-thrown after the executor has been shut down and awaited above. On the normal path
        // awaitTermination returned true, so every worker has finished and none can still be
        // touching paramMap/callback. If both waits timed out instead, that guarantee does not
        // hold -- awaitTermination returning false means by definition the pool has not
        // terminated -- which is what the second warning above reports.
        final SlackApiException fatalException = fatalError.get();
        if (fatalException != null) {
            throw fatalException;
        }
    }

    /**
     * Extracts the maximum file size configuration from parameters.
     *
     * @param paramMap the configuration parameters
     * @return the maximum file size in bytes
     */
    protected long getMaxFilesize(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(MAX_FILESIZE);
        try {
            return StringUtil.isNotBlank(value) ? Long.parseLong(value) : DEFAULT_MAX_FILESIZE;
        } catch (final NumberFormatException e) {
            return DEFAULT_MAX_FILESIZE;
        }
    }

    /**
     * Extracts, in seconds, how long {@link #storeData} should wait for queued crawl work to
     * finish before forcing shutdown.
     *
     * <p>
     * A non-numeric value falls back to {@link #DEFAULT_EXECUTOR_TIMEOUT} with a warning rather
     * than failing the crawl.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the number of seconds to wait for queued work to finish before forcing shutdown
     */
    protected int getExecutorTimeout(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(EXECUTOR_TIMEOUT);
        try {
            return StringUtil.isNotBlank(value) ? Integer.parseInt(value) : DEFAULT_EXECUTOR_TIMEOUT;
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", EXECUTOR_TIMEOUT, value, DEFAULT_EXECUTOR_TIMEOUT);
            return DEFAULT_EXECUTOR_TIMEOUT;
        }
    }

    /**
     * Determines whether errors should be ignored during crawling.
     *
     * @param paramMap the configuration parameters
     * @return true if errors should be ignored, false otherwise
     */
    protected boolean isIgnoreError(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(IGNORE_ERROR, Constants.TRUE));
    }

    /**
     * Extracts the list of supported MIME type patterns from parameters.
     *
     * @param paramMap the configuration parameters
     * @return the list of supported MIME type patterns
     */
    protected List<String> getSupportedMimeTypes(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(SUPPORTED_MIMETYPES, MATCH_ALL_MIMETYPES);
        final String effective = StringUtil.isNotBlank(value) ? value : MATCH_ALL_MIMETYPES;
        return Arrays.stream(StringUtil.split(effective, ",")).map(String::trim).collect(Collectors.toList());
    }

    /**
     * Determines whether file crawling is enabled.
     *
     * @param paramMap the configuration parameters
     * @return true if file crawling is enabled, false otherwise
     */
    protected boolean isFileCrawl(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(FILE_CRAWL, Constants.FALSE));
    }

    /**
     * Determines whether Slack-generated channel-administration messages (see
     * {@link #SYSTEM_EVENT_SUBTYPES}) should be excluded from indexing.
     *
     * <p>
     * Defaults to {@code true}, unlike every other parameter this PR adds: {@code channel_join}/
     * {@code channel_leave} and the rest of {@link #SYSTEM_EVENT_SUBTYPES} are search noise, not
     * content an operator is likely to want indexed, and four of the connectors surveyed for this
     * plugin's design exclude them by default too. A crawl run without setting this parameter
     * explicitly indexes fewer documents than it did before this parameter existed.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return true if system-event messages should be excluded, false otherwise
     */
    protected boolean isIgnoreSystemEvents(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(IGNORE_SYSTEM_EVENTS, Constants.TRUE));
    }

    /**
     * Returns whether the given message is a Slack-generated channel-administration
     * notification that {@link #isIgnoreSystemEvents} would exclude.
     *
     * @param message the message to test
     * @return true if the message's subtype is one of {@link #SYSTEM_EVENT_SUBTYPES}
     */
    protected boolean isSystemEventMessage(final Message message) {
        final String subtype = message.getSubtype();
        return subtype != null && SYSTEM_EVENT_SUBTYPES.contains(subtype);
    }

    /**
     * Extracts, in milliseconds, how long {@link #processChannelMessages} and
     * {@link #processChannelFiles} should {@link #sleep} after each processed message or file,
     * to pace a crawl against a rate-limited workspace.
     *
     * <p>
     * Overrides {@link AbstractDataStore#getReadInterval}, which reads the hardcoded key {@code
     * "readInterval"} -- camelCase, unlike every other parameter this plugin defines ({@code
     * token}, {@code include_private}, {@code connection_timeout}, {@code exclude_archived},
     * ...). Reading that key as-is would silently accept a parameter name this plugin's own
     * naming convention, and the design this parameter shipped under, never document, so this
     * reads {@link #READ_INTERVAL} instead. {@link #sleep} itself is not overridden.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the interval in milliseconds, or 0 if unset or not a number
     */
    @Override
    protected long getReadInterval(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(READ_INTERVAL);
        if (StringUtil.isBlank(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to 0.", READ_INTERVAL, value);
            return 0L;
        }
    }

    /**
     * Creates and configures a URL filter based on include/exclude patterns.
     *
     * @param paramMap the configuration parameters
     * @return the configured URL filter, or null if the {@link UrlFilter} component is not
     *         registered
     */
    protected UrlFilter getUrlFilter(final DataStoreParams paramMap) {
        final UrlFilter urlFilter;
        try {
            urlFilter = ComponentUtil.getComponent(UrlFilter.class);
        } catch (final ComponentNotFoundException e) {
            logger.warn("UrlFilter component is not registered; {} and {} will not be applied.", INCLUDE_PATTERN, EXCLUDE_PATTERN, e);
            return null;
        }
        final String include = paramMap.getAsString(INCLUDE_PATTERN);
        if (StringUtil.isNotBlank(include)) {
            urlFilter.addInclude(include);
        }
        final String exclude = paramMap.getAsString(EXCLUDE_PATTERN);
        if (StringUtil.isNotBlank(exclude)) {
            urlFilter.addExclude(exclude);
        }
        urlFilter.init(paramMap.getAsString(Constants.CRAWLING_INFO_ID));
        if (logger.isDebugEnabled()) {
            logger.debug("urlFilter: {}", urlFilter);
        }
        return urlFilter;
    }

    /**
     * Creates a fixed thread pool executor for parallel processing.
     *
     * @param nThreads the number of threads in the pool
     * @return the configured executor service
     */
    protected ExecutorService newFixedThreadPool(final int nThreads) {
        if (logger.isDebugEnabled()) {
            logger.debug("Executor Thread Pool: {}", nThreads);
        }
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(nThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Processes all messages in a channel, including threaded replies.
     *
     * <p>
     * A message {@link #isSystemEventMessage} recognizes is dropped here, before it is even
     * dispatched to {@code executorService}, when {@link #isIgnoreSystemEvents} is on: it is
     * never threadable (see {@code SlackClient#handleApiError}'s {@code thread_not_found}
     * handling), so skipping it here also avoids a wasted {@code chat.getPermalink} call that
     * {@link #processMessage} would otherwise make to resolve its URL. A dropped message does
     * not count toward {@code read_interval} pacing below, since nothing was dispatched for it.
     * </p>
     *
     * <p>
     * Sleeps for {@code read_interval} milliseconds (see {@link #getReadInterval}) after each
     * message actually dispatched, to pace the crawl against a rate-limited workspace.
     * </p>
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param executorService the executor service for parallel processing
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel to process
     * @param fatalError latch shared with {@link #storeData} for a fatal {@link SlackApiException}
     *            raised on this method's worker thread (see {@link #latchFatalError})
     */
    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client, final Team team,
            final Channel channel, final AtomicReference<SlackApiException> fatalError) {
        final boolean ignoreSystemEvents = (Boolean) configMap.get(IGNORE_SYSTEM_EVENTS);
        final long readInterval = (Long) configMap.get(READ_INTERVAL);
        client.getChannelMessages(channel.getId(), message -> {
            if (ignoreSystemEvents && isSystemEventMessage(message)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Skipping system event message (subtype={}) in channel {}", message.getSubtype(), channel.getId());
                }
                return;
            }
            safeExecute(executorService, () -> {
                try {
                    processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                    if (isThreadParent(message)) {
                        processMessageReplies(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel,
                                message);
                    }
                } catch (final SlackApiException e) {
                    latchFatalError(executorService, fatalError, e);
                }
            });
            if (readInterval > 0) {
                sleep(readInterval);
            }
        });
    }

    /**
     * Returns whether the given message is the parent of a thread. Slack
     * identifies a parent by its thread_ts being equal to its ts; a reply
     * carries the parent's thread_ts and a different ts.
     *
     * @param message the message to test
     * @return true if the message starts a thread
     */
    protected boolean isThreadParent(final Message message) {
        final String threadTs = message.getThreadTs();
        return threadTs != null && threadTs.equals(message.getTs());
    }

    /**
     * Records the first fatal Slack API error seen by any worker thread and shuts the executor
     * down immediately.
     *
     * <p>
     * Chosen over letting the backlog drain naturally: once the token itself is no longer valid,
     * every other queued or future {@code conversations.replies} call is going to fail the exact
     * same way, so continuing to run them only delays reporting a failure the crawl already knows
     * about. {@link #safeExecute} is what keeps this safe for the caller -- {@code
     * client.getChannels}/{@code getChannelMessages}/{@code getChannelFiles} keep walking and
     * dispatching on the calling thread after this runs, and a plain {@code execute} call on an
     * already-shut-down executor throws {@link RejectedExecutionException}, which would otherwise
     * surface as the crawl's reported failure instead of the {@link SlackApiException} latched
     * here.
     * </p>
     *
     * @param executorService the executor to shut down
     * @param fatalError the latch shared with {@link #storeData}; only the first error is kept, since a
     *            second worker hitting this almost certainly means the same fatal condition, not
     *            new information
     * @param e the fatal error just caught
     */
    protected void latchFatalError(final ExecutorService executorService, final AtomicReference<SlackApiException> fatalError,
            final SlackApiException e) {
        if (fatalError.compareAndSet(null, e)) {
            executorService.shutdownNow();
        }
    }

    /**
     * Submits {@code task} to {@code executorService}, discarding it silently if the executor has
     * already been shut down (see {@link #latchFatalError}) instead of letting
     * {@link RejectedExecutionException} propagate to the caller -- typically
     * {@link SlackClient}'s paging loop, one channel/message/file at a time, which is not
     * prepared to handle it and would otherwise mask the crawl's real, already-latched failure.
     *
     * @param executorService the executor to submit to
     * @param task the task to run
     */
    protected void safeExecute(final ExecutorService executorService, final Runnable task) {
        try {
            executorService.execute(task);
        } catch (final RejectedExecutionException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Discarding a task submitted after the executor was shut down for a fatal error.", e);
            }
        }
    }

    /**
     * Processes all files in a channel for indexing.
     *
     * <p>
     * Sleeps for {@code read_interval} milliseconds (see {@link #getReadInterval}) after each
     * file, to pace the crawl against a rate-limited workspace.
     * </p>
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param executorService the executor service for parallel processing
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel to process
     * @param fatalError latch shared with {@link #storeData} for a fatal {@link SlackApiException}
     *            raised on this method's worker thread (see {@link #latchFatalError})
     */
    protected void processChannelFiles(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel,
            final AtomicReference<SlackApiException> fatalError) {
        final long readInterval = (Long) configMap.get(READ_INTERVAL);
        client.getChannelFiles(channel.getId(), file -> {
            safeExecute(executorService, () -> {
                try {
                    processFile(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, file);
                } catch (final SlackApiException e) {
                    latchFatalError(executorService, fatalError, e);
                }
            });
            if (readInterval > 0) {
                sleep(readInterval);
            }
        });
    }

    /**
     * Processes all replies to a threaded message.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the thread
     * @param parentMessage the parent message of the thread
     */
    protected void processMessageReplies(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final SlackClient client, final Team team, final Channel channel,
            final Message parentMessage) {
        client.getMessageReplies(channel.getId(), parentMessage.getThreadTs(), message -> {
            processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
        });
    }

    /**
     * Builds the map exposed to crawl scripts, withholding credentials and
     * network configuration.
     *
     * <p>
     * {@link AbstractDataStore#convertValue} returns a value verbatim when a
     * script template matches a parameter key exactly, so a copy of the raw
     * parameter map would let a script such as {@code content=token} index
     * the OAuth token. The keys in {@link #SECRET_PARAMS} are withheld
     * instead of copied.
     * </p>
     *
     * @param paramMap the data store parameters
     * @return a new map safe to expose to scripts
     */
    protected Map<String, Object> newResultMap(final DataStoreParams paramMap) {
        final Map<String, Object> resultMap = new LinkedHashMap<>();
        paramMap.asMap().forEach((k, v) -> {
            if (!SECRET_PARAMS.contains(k)) {
                resultMap.put(k, v);
            }
        });
        return resultMap;
    }

    /**
     * Resolves the URL to record via {@code FailureUrlService} for a failed crawl, and, when the
     * failure asks to abort, clears this crawl's stop flag.
     *
     * <p>
     * Shared by both {@code catch (CrawlingAccessException)} blocks in {@link #processMessage}
     * and {@link #processFile}. Before this, both ignored {@link DataStoreCrawlingException#getUrl()}
     * and {@link DataStoreCrawlingException#aborted()} on a target that carried them, always
     * recording the caller-computed {@code fallbackUrl} (a message's permalink or a file's own
     * permalink) even when the exception named a more precise URL, and never reacting to a
     * request to abort. Matches the pattern in {@code fess-ds-example}'s
     * {@code ExampleDataStore}: that class uses a local {@code running} flag, which cannot be a
     * plain local variable here because {@link SlackDataStore} dispatches message and file
     * processing to worker threads (see {@code storeData}'s {@code executorService}), so one
     * caller's stack frame would not be seen by the others -- hence the shared
     * {@link AtomicBoolean}.
     * </p>
     *
     * <p>
     * Deliberately <em>not</em> {@link #stop()}. That flips the inherited
     * {@link org.codelibs.fess.ds.AbstractDataStore#alive} flag, which is assigned {@code true}
     * exactly once (at field initialisation) and is never reset by Fess. Because a data store is
     * a LastaDi singleton reused by every crawl for the lifetime of the JVM, aborting one crawl
     * that way would leave every subsequent crawl of every Slack {@code DataConfig} walking zero
     * channels and reporting success, until Fess is restarted.
     * </p>
     *
     * @param target the (possibly unwrapped) crawling exception being handled
     * @param fallbackUrl the URL to use when {@code target} carries none of its own
     * @param crawlAlive this crawl's stop flag, cleared when {@code target} asks to abort
     * @return the URL to record via {@code FailureUrlService}
     */
    protected String resolveFailureUrl(final Throwable target, final String fallbackUrl, final AtomicBoolean crawlAlive) {
        if (target instanceof final DataStoreCrawlingException dce) {
            if (dce.aborted()) {
                crawlAlive.set(false);
            }
            return dce.getUrl();
        }
        return fallbackUrl;
    }

    /**
     * Processes a single message for indexing, extracting content and metadata.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the message
     * @param message the message to process
     */
    protected void processMessage(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final Message message) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        // getMessagePermalink is resolved outside the main try/catch below because
        // CrawlerStatsHelper#begin requires StatsKeyObject to already carry a non-null
        // id; falling through to the main catch (Throwable) with a not-yet-constructed
        // statsKey would only trade this failure for an IllegalArgumentException out of
        // CrawlerStatsHelper itself. An unexpected failure here still does not abort the
        // channel's crawl: it is logged and a channel/timestamp-based identifier is used
        // in its place.
        String url;
        try {
            url = getMessagePermalink(client, team, channel, message);
        } catch (final Exception e) {
            logger.warn("Failed to get a permalink for a message in channel: {}", channel.getId(), e);
            url = channel.getId() + "/" + message.getTs();
        }
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);

            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            logger.info("Crawling URL: {}", url);

            final Map<String, Object> resultMap = newResultMap(paramMap);
            final Map<String, Object> messageMap = new HashMap<>();

            final String messageText = getMessageText(message);
            final String username = getMessageUsername(client, message);
            messageMap.put(MESSAGE_TITLE, StringUtil.EMPTY);
            messageMap.put(MESSAGE_TEXT, messageText);
            // messageMap.put(MESSAGE_TEAM, team.getName());
            messageMap.put(MESSAGE_TIMESTAMP, getMessageTimestamp(message));
            messageMap.put(MESSAGE_USER, username);
            messageMap.put(MESSAGE_CHANNEL, channel.getName());
            messageMap.put(MESSAGE_PERMALINK, url);
            messageMap.put(MESSAGE_ATTACHMENTS, getMessageAttachmentsText(message));
            resultMap.put(MESSAGE, messageMap);

            crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

            if (logger.isDebugEnabled()) {
                logger.debug("messageMap: {}", messageMap);
            }

            final String scriptType = getScriptType(paramMap);
            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }

            crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

            if (logger.isDebugEnabled()) {
                logger.debug("dataMap: {}", dataMap);
            }

            if (dataMap.get("url") instanceof String statsUrl) {
                statsKey.setUrl(statsUrl);
            }

            callback.store(paramMap, dataMap);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final CrawlingAccessException e) {
            logger.warn("Crawling Access Exception at : {}", dataMap, e);

            Throwable target = e;
            if (target instanceof MultipleCrawlingAccessException ex) {
                final Throwable[] causes = ex.getCauses();
                if (causes.length > 0) {
                    target = causes[causes.length - 1];
                }
            }

            String errorName;
            final Throwable cause = target.getCause();
            if (cause != null) {
                errorName = cause.getClass().getCanonicalName();
            } else {
                errorName = target.getClass().getCanonicalName();
            }

            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, errorName, resolveFailureUrl(target, url, (AtomicBoolean) configMap.get(CRAWL_ALIVE)),
                    target);
            crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        } catch (final Throwable t) {
            logger.warn("Crawling Access Exception at : {}", dataMap, t);
            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    /**
     * Processes a single file for indexing, extracting content and metadata.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the file
     * @param file the file to process
     */
    protected void processFile(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final File file) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final String url = file.getPermalink();
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);

            final String mimeType = file.getMimetype();
            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            logger.info("Crawling URL: {}", url);

            final boolean ignoreError = (Boolean) configMap.get(IGNORE_ERROR);

            final Map<String, Object> resultMap = newResultMap(paramMap);
            final Map<String, Object> fileMap = new HashMap<>();

            final long maxFilesize = (Long) configMap.get(MAX_FILESIZE);
            if (file.getSize() > maxFilesize) {
                throw new MaxLengthExceededException(
                        "The content length (" + file.getSize() + " byte) is over " + maxFilesize + " byte. The url is " + url);
            }

            if (configMap.getOrDefault(SUPPORTED_MIMETYPES, EMPTY_LIST) instanceof List<?> supportedMimetypes
                    && supportedMimetypes.stream().map(o -> o.toString()).noneMatch(mimeType::matches)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} is not an indexing target.", mimeType);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            final String fileContent = getFileContent(client, file, ignoreError);
            fileMap.put(MESSAGE_TITLE, getFileTitle(file));
            fileMap.put(MESSAGE_TEXT, getFileText(file, fileContent));
            // fileMap.put(MESSAGE_TEAM, team.getName());
            fileMap.put(MESSAGE_TIMESTAMP, getFileTimestamp(file));
            fileMap.put(MESSAGE_USER, getFileUsername(client, file));
            fileMap.put(MESSAGE_CHANNEL, channel.getName());
            fileMap.put(MESSAGE_PERMALINK, file.getPermalink());
            fileMap.put(MESSAGE_ATTACHMENTS, "");
            resultMap.put(MESSAGE, fileMap);

            crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

            if (logger.isDebugEnabled()) {
                logger.debug("fileMap: {}", fileMap);
            }

            final String scriptType = getScriptType(paramMap);
            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }

            crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

            if (logger.isDebugEnabled()) {
                logger.debug("dataMap: {}", dataMap);
            }

            if (dataMap.get("url") instanceof String statsUrl) {
                statsKey.setUrl(statsUrl);
            }
            callback.store(paramMap, dataMap);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final CrawlingAccessException e) {
            logger.warn("Crawling Access Exception at : {}", dataMap, e);

            Throwable target = e;
            if (target instanceof MultipleCrawlingAccessException ex) {
                final Throwable[] causes = ex.getCauses();
                if (causes.length > 0) {
                    target = causes[causes.length - 1];
                }
            }

            String errorName;
            final Throwable cause = target.getCause();
            if (cause != null) {
                errorName = cause.getClass().getCanonicalName();
            } else {
                errorName = target.getClass().getCanonicalName();
            }

            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, errorName, resolveFailureUrl(target, url, (AtomicBoolean) configMap.get(CRAWL_ALIVE)),
                    target);
            crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        } catch (final Throwable t) {
            logger.warn("Crawling Access Exception at : {}", dataMap, t);
            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    /**
     * Extracts the text content from a message.
     *
     * @param message the message to extract text from
     * @return the message text or empty string if null
     */
    protected String getMessageText(final Message message) {
        final String text = message.getText();
        return text != null ? text : "";
    }

    /**
     * Converts a message timestamp to a Date object.
     *
     * <p>
     * A message with no {@code ts} can still reach here with a non-empty permalink -- see
     * {@link #getMessagePermalink} -- so this must not assume {@code ts} is present. Mirrors
     * {@link #getFileTimestamp}, which returns {@code null} rather than throwing when a file
     * carries neither {@code created} nor {@code timestamp}: the message still indexes, just
     * without a timestamp, instead of failing with an unhelpful {@link NullPointerException}
     * recorded under an empty URL.
     * </p>
     *
     * @param message the message containing the timestamp
     * @return the timestamp as a Date object, or {@code null} if the message carries no {@code ts}
     */
    protected Date getMessageTimestamp(final Message message) {
        final String ts = message.getTs();
        if (ts == null) {
            return null;
        }
        return new Date(Math.round(Double.parseDouble(ts) * 1000));
    }

    /**
     * Converts a file's creation time to a Date object. Slack documents
     * {@code timestamp} as deprecated and kept only for backwards
     * compatibility, so {@code created} is preferred; {@code timestamp} is
     * used only when {@code created} is absent.
     *
     * @param file the file containing the creation time
     * @return the creation time as a Date object, or null if the file carries
     *         neither {@code created} nor {@code timestamp}
     */
    protected Date getFileTimestamp(final File file) {
        final Long created = file.getCreated();
        if (created != null) {
            return new Date(created.longValue() * 1000L);
        }
        final Long timestamp = file.getTimestamp();
        if (timestamp != null) {
            return new Date(timestamp.longValue() * 1000L);
        }
        return null;
    }

    /**
     * Extracts the username from a message, handling different message types.
     *
     * @param client the Slack client for user lookups
     * @param message the message to extract username from
     * @return the username or empty string if not found
     */
    public String getMessageUsername(final SlackClient client, final Message message) {
        try {
            if (message.getUser() != null) {
                return getUsername(client, message.getUser());
            }
            if (message.getSubtype() != null) {
                if ("bot_message".equals(message.getSubtype())) {
                    return client.getBot(message.getBotId()).getName();
                }
                if ("file_comment".equals(message.getSubtype())) {
                    return getUsername(client, message.getComment().getUser());
                }
            }
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
        }
        return StringUtil.EMPTY;
    }

    /**
     * Extracts the username from a file upload.
     *
     * @param client the Slack client for user lookups
     * @param file the file to extract username from
     * @return the username or empty string if not found
     */
    public String getFileUsername(final SlackClient client, final File file) {
        try {
            if (file.getUser() != null) {
                return getUsername(client, file.getUser());
            }
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
        }
        return StringUtil.EMPTY;
    }

    /**
     * Retrieves a user's display name by user ID.
     *
     * @param client the Slack client for user lookups
     * @param userId the user ID to look up
     * @return the user's display name or the user ID if lookup fails
     */
    protected String getUsername(final SlackClient client, final String userId) {
        try {
            final User user = client.getUser(userId);
            if (user != null) {
                final Profile profile = user.getProfile();
                if (profile != null) {
                    if (StringUtil.isNotBlank(profile.getDisplayName())) {
                        return profile.getDisplayName();
                    }
                    if (StringUtil.isNotBlank(profile.getRealName())) {
                        return profile.getRealName();
                    }
                }
                if (StringUtil.isNotBlank(user.getRealName())) {
                    return user.getRealName();
                }
                if (StringUtil.isNotBlank(user.getName())) {
                    return user.getName();
                }
            }
        } catch (final ExecutionException | UncheckedExecutionException | InvalidCacheLoadException e) {
            logger.warn("Failed to get username from user: {}", userId, e);
        }
        return userId;
    }

    /**
     * Extracts text content from message attachments.
     *
     * @param message the message containing attachments
     * @return the concatenated attachment text or empty string if no attachments
     */
    protected String getMessageAttachmentsText(final Message message) {
        final List<Attachment> attachments = message.getAttachments();
        if (attachments == null) {
            return StringUtil.EMPTY;
        }
        return attachments.stream().map(Attachment::getFallback).filter(StringUtil::isNotBlank).collect(Collectors.joining("\n"));
    }

    /**
     * Generates or retrieves the permalink URL for a message.
     *
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the message
     * @param message the message to get permalink for
     * @return the permalink URL for the message, or an empty string if the message carries no
     *         timestamp to build one from
     */
    public String getMessagePermalink(final SlackClient client, final Team team, final Channel channel, final Message message) {
        String permalink = message.getPermalink();
        if (permalink == null) {
            if (message.getTs() == null) {
                return StringUtil.EMPTY;
            }
            if (team == null) {
                permalink = client.getPermalink(channel.getId(), message.getTs());
            } else {
                permalink =
                        "https://" + team.getDomain() + ".slack.com/archives/" + channel.getId() + "/p" + message.getTs().replace(".", "");
            }
        }
        return permalink;
    }

    /**
     * Builds the indexed title for a file: its name and its Slack-assigned title, joined by a
     * space and each skipped when blank.
     *
     * @param file the file being indexed
     * @return the file name and title joined by a space
     */
    protected String getFileTitle(final File file) {
        return Stream.of(file.getName(), file.getTitle()).filter(StringUtil::isNotBlank).collect(Collectors.joining(" "));
    }

    /**
     * Builds the indexed text for a file: its name followed by its extracted content.
     *
     * @param file the file being indexed
     * @param fileContent the file's extracted content; {@link #getFileContent} never returns
     *            null, only {@link StringUtil#EMPTY} when extraction yields nothing
     * @return the file name and content joined by a newline, without a trailing newline when
     *         the content is blank
     */
    protected String getFileText(final File file, final String fileContent) {
        return Stream.of(file.getName(), fileContent).filter(StringUtil::isNotBlank).collect(Collectors.joining("\n"));
    }

    /**
     * Downloads and extracts content from a Slack file.
     *
     * @param client the Slack client for file download
     * @param file the file to extract content from
     * @param ignoreError whether to ignore extraction errors
     * @return the extracted file content or empty string if extraction fails
     */
    protected String getFileContent(final SlackClient client, final File file, final boolean ignoreError) {
        if (file.getPermalink() != null) {
            final String mimeType = file.getMimetype().trim();
            final String fileUrl = file.getUrlPrivateDownload();
            try (final CurlResponse response = client.getFileResponse(fileUrl)) {
                if (response.getHttpStatusCode() != 200) {
                    throw new SlackDataStoreException(
                            "HTTP Status " + response.getHttpStatusCode() + " : failed to get the file from " + fileUrl);
                }
                try (final InputStream in = response.getContentAsStream()) {
                    return ComponentUtil.getExtractorFactory()
                            .builder(in, null)
                            .mimeType(mimeType)
                            .extractorName(extractorName)
                            .extract()
                            .getContent();
                }
            } catch (final Exception e) {
                if (!ignoreError && !ComponentUtil.getFessConfig().isCrawlerIgnoreContentException()) {
                    throw new DataStoreCrawlingException(file.getPermalink(), "Failed to get contents: " + file.getName(), e);
                }
                if (logger.isDebugEnabled()) {
                    logger.warn("Failed to get contents: {}", file.getName(), e);
                } else {
                    logger.warn("Failed to get contents: {}. {}", file.getName(), e.getMessage());
                }
                return StringUtil.EMPTY;
            }
        }
        return StringUtil.EMPTY;
    }

}
