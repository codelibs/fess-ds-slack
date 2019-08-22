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
        final String token = getToken(paramMap);
        if (token.isEmpty()) {
            logger.warn("parameter \"" + TOKEN_PARAM + "\" is required");
            return;
        }

        final SlackClient client = new SlackClient(token, paramMap);
        final Team team = client.team.info().execute().getTeam();
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
                executorService
                        .execute(() -> processChannelMessages(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel));
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
            final SlackClient client, final Team team, final Channel channel) {
        client.getChannelMessages(channel.getId(), message -> {
            processMessage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
            if (message.getThreadTs() != null) {
                processMessageReplies(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, team, channel, message);
            }
        });
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
            messageMap.put(MESSAGE_USER, client.getMessageUsername(message));
            messageMap.put(MESSAGE_CHANNEL, channel.getName());
            messageMap.put(MESSAGE_PERMALINK, client.getMessagePermalink(team, channel, message));
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

    protected String getMessageAttachmentsText(final Message message) {
        final List<Attachment> attachments = message.getAttachments();
        if (attachments == null) {
            return "";
        }
        final List<String> fallbacks = attachments.stream().map(Attachment::getFallback).collect(Collectors.toList());
        return String.join("\n", fallbacks);
    }

    protected String getToken(final Map<String, String> paramMap) {
        if (paramMap.containsKey(TOKEN_PARAM)) {
            return paramMap.get(TOKEN_PARAM);
        }
        return StringUtil.EMPTY;
    }

}
