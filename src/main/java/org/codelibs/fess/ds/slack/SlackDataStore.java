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

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.SlackClient;
import org.codelibs.fess.ds.slack.api.type.Attachment;
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
    protected static final String NUMBER_OF_THREADS = "number_of_threads";

    // scripts
    protected static final String MESSAGE = "message";
    protected static final String MESSAGE_TEXT = "text";
    protected static final String MESSAGE_TIMESTAMP = "timestamp";
    protected static final String MESSAGE_USER = "user";
    protected static final String MESSAGE_CHANNEL = "channel";
    protected static final String MESSAGE_PERMALINK = "permalink";
    protected static final String MESSAGE_ATTACHMENTS = "attachments";


    protected String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final SlackClient client = new SlackClient(paramMap);
        final Team team = client.getTeam();
        storeMessages(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team);
    }

    protected ExecutorService newFixedThreadPool(final int nThreads) {
        if (logger.isDebugEnabled()) {
            logger.debug("Executor Thread Pool: " + nThreads);
        }
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(nThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    protected void storeMessages(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, String> paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final SlackClient client, final Team team) {
        final ExecutorService executorService = newFixedThreadPool(Integer.parseInt(paramMap.getOrDefault(NUMBER_OF_THREADS, "1")));
        try {
            client.getChannels(channel -> {
                processChannelMessages(dataConfig, callback, paramMap, scriptMap, defaultDataMap, executorService, client, team, channel);
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

    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
                                          final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel) {
        client.getChannelMessages(channel.getId(), message -> {
                    executorService.execute(() -> {
                        processMessage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                        if (message.getThreadTs() != null) {
                            processMessageReplies(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
                        }
                    });
                }
            );
    }

    protected void processMessageReplies(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, String> paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final Message parentMessage) {
        client.getMessageReplies(channel.getId(), parentMessage.getThreadTs(), message -> {
                processMessage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
            });
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
            messageMap.put(MESSAGE_TEXT, getMessageText(message));
            messageMap.put(MESSAGE_TIMESTAMP, getMessageTimestamp(message));
            messageMap.put(MESSAGE_USER, getMessageUsername(client, message));
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

    protected String getMessageText(final Message message) {
        final String text = message.getText();
        return text != null ? text : "";
    }

    protected Date getMessageTimestamp(final Message message) {
        return new Date(Math.round(Double.parseDouble(message.getTs()) * 1000));
    }

    public String getMessageUsername(final SlackClient client, final Message message) {
        if (message.getUsername() != null) {
            return message.getUsername();
        }
        try {
            if (message.getSubtype() != null) {
                if (message.getSubtype().equals("bot_message")) {
                    return client.getBot(message.getBotId()).getName();
                } else if (message.getSubtype().equals("file_comment")) {
                    final User user = client.getUser(message.getComment().getUser());
                    return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName() : user.getProfile().getRealName();
                }
            }
            final User user = client.getUser(message.getUsername());
            return !user.getProfile().getDisplayName().isEmpty() ? user.getProfile().getDisplayName() : user.getProfile().getRealName();
        } catch(final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
            return StringUtil.EMPTY;
        }
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

}
