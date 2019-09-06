/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
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

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MaxLengthExceededException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.extractor.Extractor;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.es.config.exentity.DataConfig;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.util.ComponentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.EMPTY_LIST;

public class SlackDataStore extends AbstractDataStore {

    private static final Logger logger = LoggerFactory.getLogger(SlackDataStore.class);

    protected static final long DEFAULT_MAX_FILESIZE = 10000000L; // 10m

    // parameters
    protected static final String IGNORE_ERROR = "ignore_error";
    protected static final String SUPPORTED_MIMETYPES = "supported_mimetypes";
    protected static final String INCLUDE_PATTERN = "include_pattern";
    protected static final String EXCLUDE_PATTERN = "exclude_pattern";
    protected static final String URL_FILTER = "url_filter";
    protected static final String NUMBER_OF_THREADS = "number_of_threads";
    protected static final String MAX_FILESIZE = "max_filesize";
    protected static final String FILE_CRAWL = "file_crawl";

    // scripts
    protected static final String MESSAGE = "message";
    protected static final String MESSAGE_TITLE = "title";
    protected static final String MESSAGE_TEXT = "text";
    protected static final String MESSAGE_TIMESTAMP = "timestamp";
    protected static final String MESSAGE_USER = "user";
    protected static final String MESSAGE_CHANNEL = "channel";
    protected static final String MESSAGE_PERMALINK = "permalink";
    protected static final String MESSAGE_ATTACHMENTS = "attachments";

    protected String extractorName = "tikaExtractor";

    @Override
    public String getName() {
        return "Slack";
    }

    public void setExtractorName(final String extractorName) {
        this.extractorName = extractorName;
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
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

        final SlackClient client = new SlackClient(paramMap);
        final Team team = client.getTeam();

        final ExecutorService executorService = newFixedThreadPool(Integer.parseInt(paramMap.getOrDefault(NUMBER_OF_THREADS, "1")));
        try {
            final boolean fileCrawl = (Boolean) configMap.get(FILE_CRAWL);
            client.getChannels(channel -> {
                processChannelMessages(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team, channel);
                if (fileCrawl) {
                    processChannelFiles(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client, team, channel);
                }
            });

            if (logger.isDebugEnabled()) {
                logger.debug("Shutting down thread executor.");
            }

            executorService.shutdown();
            executorService.awaitTermination(60, TimeUnit.SECONDS);
        } catch(final InterruptedException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Interrupted.", e);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    protected long getMaxFilesize(final Map<String, String> paramMap) {
        final String value = paramMap.get(MAX_FILESIZE);
        try {
            return StringUtil.isNotBlank(value) ? Long.parseLong(value) : DEFAULT_MAX_FILESIZE;
        } catch (final NumberFormatException e) {
            return DEFAULT_MAX_FILESIZE;
        }
    }

    protected boolean isIgnoreError(final Map<String, String> paramMap) {
        return paramMap.getOrDefault(IGNORE_ERROR, Constants.TRUE).equalsIgnoreCase(Constants.TRUE);
    }

    private List<String> getSupportedMimeTypes(final Map<String, String> paramMap) {
        return Arrays.stream(StringUtil.split(paramMap.getOrDefault(SUPPORTED_MIMETYPES, ".*"), ","))
                .map(String::trim).collect(Collectors.toList());
    }

    protected boolean isFileCrawl(final Map<String, String> paramMap) {
        return paramMap.getOrDefault(FILE_CRAWL, Constants.FALSE).equalsIgnoreCase(Constants.TRUE);
    }

    protected UrlFilter getUrlFilter(final Map<String, String> paramMap) {
        final UrlFilter urlFilter = ComponentUtil.getComponent(UrlFilter.class);
        final String include = paramMap.get(INCLUDE_PATTERN);
        if (StringUtil.isNotBlank(include)) {
            urlFilter.addInclude(include);
        }
        final String exclude = paramMap.get(EXCLUDE_PATTERN);
        if (StringUtil.isNotBlank(exclude)) {
            urlFilter.addExclude(exclude);
        }
        urlFilter.init(paramMap.get(Constants.CRAWLING_INFO_ID));
        if (logger.isDebugEnabled()) {
            logger.debug("urlFilter: {}", urlFilter);
        }
        return urlFilter;
    }

    protected ExecutorService newFixedThreadPool(final int nThreads) {
        if (logger.isDebugEnabled()) {
            logger.debug("Executor Thread Pool: " + nThreads);
        }
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(nThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,final Map<String, Object> configMap,
                                          final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
                                          final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel) {
        client.getChannelMessages(channel.getId(), message -> {
                    executorService.execute(() -> {
                        processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                        if (message.getThreadTs() != null) {
                            processMessageReplies(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                        }
                    });
                }
        );
    }

    protected void processChannelFiles(final DataConfig dataConfig, final IndexUpdateCallback callback,final Map<String, Object> configMap,
                                       final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
                                       final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel) {
        client.getChannelFiles(channel.getId(), file -> {
                    executorService.execute(() -> {
                        processFile(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, channel, file);
                    });
                }
        );
    }

    protected void processMessageReplies(final DataConfig dataConfig, final IndexUpdateCallback callback,
                                         final Map<String, Object> configMap, final Map<String, String> paramMap, final Map<String, String> scriptMap,
                                         final Map<String, Object> defaultDataMap, final SlackClient client, final Team team, final Channel channel,
                                         final Message parentMessage) {
        client.getMessageReplies(channel.getId(), parentMessage.getThreadTs(), message -> {
            processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
        });
    }

    protected void processMessage(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
                                  final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
                                  final SlackClient client, final Team team, final Channel channel, final Message message) {
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final String url = getMessagePermalink(client, team, channel, message);
        try {

            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                return;
            }

            logger.info("Crawling URL: {}", url);

            final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap);
            final Map<String, Object> messageMap = new HashMap<>();

            final String messageText = getMessageText(message);
            final String username = getMessageUsername(client, message);
            messageMap.put(MESSAGE_TITLE, StringUtil.EMPTY);
            messageMap.put(MESSAGE_TEXT, messageText);
            messageMap.put(MESSAGE_TIMESTAMP, getMessageTimestamp(message));
            messageMap.put(MESSAGE_USER, username);
            messageMap.put(MESSAGE_CHANNEL, channel.getName());
            messageMap.put(MESSAGE_PERMALINK, url);
            messageMap.put(MESSAGE_ATTACHMENTS, getMessageAttachmentsText(message));
            resultMap.put(MESSAGE, messageMap);

            if (logger.isDebugEnabled()) {
                logger.debug("messageMap: {}", messageMap);
            }

            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("dataMap: {}", dataMap);
            }

            callback.store(paramMap, dataMap);
        } catch (final CrawlingAccessException e) {
            logger.warn("Crawling Access Exception at : " + dataMap, e);
        }
    }

    protected void processFile(final DataConfig dataConfig, final IndexUpdateCallback callback,final Map<String, Object> configMap,
                               final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final SlackClient client,
                               final Channel channel, final File file) {
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final String url = file.getPermalink();
        try {
            final String mimeType = file.getMimetype();
            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                return;
            }

            logger.info("Crawling URL: {}", url);

            final boolean ignoreError = (Boolean) configMap.get(IGNORE_ERROR);

            final Map<String, Object> resultMap = new LinkedHashMap<>(paramMap);
            final Map<String, Object> fileMap = new HashMap<>();

            final long maxFilesize = (Long) configMap.get(MAX_FILESIZE);
            if (file.getSize() > maxFilesize) {
                throw new MaxLengthExceededException("The content length (" + file.getSize() + " byte) is over " + maxFilesize
                        + " byte. The url is " + url);
            }

            final List<String> supportedMimetypes = (List<String>) configMap.getOrDefault(SUPPORTED_MIMETYPES, EMPTY_LIST);
            if (supportedMimetypes.stream().noneMatch(mimeType::matches)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} is not an indexing target.", mimeType);
                }
                return;
            }

            final String fileContent = getFileContent(client, file, ignoreError);
            fileMap.put(MESSAGE_TITLE, file.getName() + " " + file.getTitle());
            fileMap.put(MESSAGE_TEXT, fileContent);
            fileMap.put(MESSAGE_TIMESTAMP, getFileTimestamp(file));
            fileMap.put(MESSAGE_USER, file.getUser());
            fileMap.put(MESSAGE_CHANNEL, channel.getName());
            fileMap.put(MESSAGE_PERMALINK, file.getPermalink());
            fileMap.put(MESSAGE_ATTACHMENTS, "");
            resultMap.put(MESSAGE, fileMap);

            if (logger.isDebugEnabled()) {
                logger.debug("fileMap: {}", fileMap);
            }

            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }
            if (logger.isDebugEnabled()) {
                logger.debug("dataMap: {}", dataMap);
            }

            callback.store(paramMap, dataMap);
        } catch (final CrawlingAccessException e) {
            logger.warn("Crawling Access Exception at : " + dataMap, e);

            Throwable target = e;
            if (target instanceof MultipleCrawlingAccessException) {
                final Throwable[] causes = ((MultipleCrawlingAccessException) target).getCauses();
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
        } catch (final Throwable t) {
            logger.warn("Crawling Access Exception at : " + dataMap, t);
            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
        }
    }

    protected String getMessageText(final Message message) {
        final String text = message.getText();
        return text != null ? text : "";
    }

    protected Date getMessageTimestamp(final Message message) {
        return new Date(Math.round(Double.parseDouble(message.getTs()) * 1000));
    }

    protected Date getFileTimestamp(final File file) {
        return new Date(file.getTimestamp() * 1000L);
    }

    public String getMessageUsername(final SlackClient client, final Message message) {
        try {
            if (message.getUser() != null) {
                try {
                    final User user = client.getUser(message.getUser());
                    if(user.getProfile().getDisplayName() != null) {
                        return user.getProfile().getDisplayName();
                    }
                    if(user.getRealName() != null) {
                        return user.getRealName();
                    }
                    if(user.getName() != null) {
                        return user.getName();
                    }
                } catch (ExecutionException e) {
                    logger.warn("Failed to get username from messages.", e);
                }
                return message.getUser();
            }
            if (message.getSubtype() != null) {
                if (message.getSubtype().equals("bot_message")) {
                    return client.getBot(message.getBotId()).getName();
                } else if (message.getSubtype().equals("file_comment")) {
                    final User user = client.getUser(message.getComment().getUser());
                    return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName() : user.getProfile().getRealName();
                }
            }
        } catch(final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
        }
        return StringUtil.EMPTY;
    }

    protected String getMessageAttachmentsText(final Message message) {
        final List<Attachment> attachments = message.getAttachments();
        if (attachments == null) {
            return "";
        }
        final List<String> fallbacks = attachments.stream().map(Attachment::getFallback).collect(Collectors.toList());
        return String.join("\n", fallbacks);
    }

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

    protected String getFileContent(final SlackClient client, final File file, final boolean ignoreError) {
        if (file.getPermalink() != null) {
            final String mimeType = file.getMimetype();
            if(mimeType.startsWith("image")) {
                return StringUtil.EMPTY;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Downloading the file :" + file.getName() );
            }

            try (final InputStream in = client.getFileContent(file.getUrlPrivateDownload())) {
                Extractor extractor = ComponentUtil.getExtractorFactory().getExtractor(mimeType);
                if (extractor == null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("use a default extractor as {} by {}", extractorName, mimeType);
                    }
                    extractor = ComponentUtil.getComponent(extractorName);
                }
                return extractor.getText(in, null).getContent();
            } catch (final Exception e) {
                if (ignoreError) {
                    logger.warn("Failed to get contents: " + file.getName(), e);
                    return StringUtil.EMPTY;
                } else {
                    throw new DataStoreCrawlingException(file.getPermalink(), "Failed to get contents: " + file.getName(), e);
                }
            }
        }
        return StringUtil.EMPTY;
    }
}
