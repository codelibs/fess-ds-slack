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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;

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
    /** Parameter name for thread pool size. */
    protected static final String NUMBER_OF_THREADS = "number_of_threads";
    /** Parameter name for maximum file size. */
    protected static final String MAX_FILESIZE = "max_filesize";
    /** Parameter name for enabling file crawling. */
    protected static final String FILE_CRAWL = "file_crawl";

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
        configMap.put(URL_FILTER, getUrlFilter(paramMap));
        if (logger.isDebugEnabled()) {
            logger.debug("configMap: {}", configMap);
        }

        final ExecutorService executorService = newFixedThreadPool(Integer.parseInt(paramMap.getAsString(NUMBER_OF_THREADS, "1")));
        try (final SlackClient client = new SlackClient(paramMap)) {
            final Team team = client.getTeam();
            final boolean fileCrawl = (Boolean) configMap.get(FILE_CRAWL);
            client.getChannels(channel -> {
                processChannelMessages(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team,
                        channel);
                if (fileCrawl) {
                    processChannelFiles(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team,
                            channel);
                }
            });

            if (logger.isDebugEnabled()) {
                logger.debug("Shutting down thread executor.");
            }

            executorService.shutdown();
            executorService.awaitTermination(60, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            throw new InterruptedRuntimeException(e);
        } finally {
            executorService.shutdownNow();
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
     * Determines whether errors should be ignored during crawling.
     *
     * @param paramMap the configuration parameters
     * @return true if errors should be ignored, false otherwise
     */
    protected boolean isIgnoreError(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(IGNORE_ERROR, Constants.TRUE));
    }

    private List<String> getSupportedMimeTypes(final DataStoreParams paramMap) {
        return Arrays.stream(StringUtil.split(paramMap.getAsString(SUPPORTED_MIMETYPES, ".*"), ","))
                .map(String::trim)
                .collect(Collectors.toList());
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
     * Creates and configures a URL filter based on include/exclude patterns.
     *
     * @param paramMap the configuration parameters
     * @return the configured URL filter
     */
    protected UrlFilter getUrlFilter(final DataStoreParams paramMap) {
        final UrlFilter urlFilter = ComponentUtil.getComponent(UrlFilter.class);
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
     */
    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client, final Team team,
            final Channel channel) {
        client.getChannelMessages(channel.getId(), message -> {
            executorService.execute(() -> {
                processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                if (message.getThreadTs() != null) {
                    processMessageReplies(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel,
                            message);
                }
            });
        });
    }

    /**
     * Processes all files in a channel for indexing.
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
     */
    protected void processChannelFiles(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel) {
        client.getChannelFiles(channel.getId(), file -> {
            executorService.execute(() -> {
                processFile(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, file);
            });
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
        final String url = getMessagePermalink(client, team, channel, message);
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

            final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap.asMap());
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
            failureUrlService.store(dataConfig, errorName, url, target);
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

            final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap.asMap());
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
            fileMap.put(MESSAGE_TITLE, file.getName() + " " + file.getTitle());
            fileMap.put(MESSAGE_TEXT, file.getName() + "\n" + fileContent);
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
            failureUrlService.store(dataConfig, errorName, url, target);
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
     * @param message the message containing the timestamp
     * @return the timestamp as a Date object
     */
    protected Date getMessageTimestamp(final Message message) {
        return new Date(Math.round(Double.parseDouble(message.getTs()) * 1000));
    }

    /**
     * Converts a file timestamp to a Date object.
     *
     * @param file the file containing the timestamp
     * @return the timestamp as a Date object
     */
    protected Date getFileTimestamp(final File file) {
        return new Date(file.getTimestamp() * 1000L);
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
                    final User user = client.getUser(message.getComment().getUser());
                    return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName()
                            : user.getProfile().getRealName();
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
            if (user.getProfile().getDisplayName() != null) {
                return user.getProfile().getDisplayName();
            }
            if (user.getRealName() != null) {
                return user.getRealName();
            }
            if (user.getName() != null) {
                return user.getName();
            }
        } catch (final ExecutionException e) {
            logger.warn("Failed to get username from user.", e);
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
            return "";
        }
        final List<String> fallbacks = attachments.stream().map(Attachment::getFallback).collect(Collectors.toList());
        return String.join("\n", fallbacks);
    }

    /**
     * Generates or retrieves the permalink URL for a message.
     *
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the message
     * @param message the message to get permalink for
     * @return the permalink URL for the message
     */
    public String getMessagePermalink(final SlackClient client, final Team team, final Channel channel, final Message message) {
        String permalink = message.getPermalink();
        if (permalink == null) {
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
