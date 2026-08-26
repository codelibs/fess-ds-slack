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
package org.codelibs.fess.ds.slack.api;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Holds the per-client request configuration for Slack API calls: OAuth
 * credentials, proxy settings, connection/read timeouts, and retry
 * behaviour. It carries more than authentication, so it lives under a name
 * that does not imply otherwise.
 */
public class RequestContext {

    /**
     * Default connection timeout in milliseconds.
     *
     * <p>
     * Public because this is the single source of truth for the default: {@code SlackClient}
     * reads it directly rather than declaring its own copy, so the value used by a real crawl
     * and the value this class falls back to when unconfigured can never drift apart.
     * </p>
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT = 20000;

    /** Default read timeout in milliseconds. See {@link #DEFAULT_CONNECTION_TIMEOUT} for why this is public. */
    public static final int DEFAULT_READ_TIMEOUT = 20000;

    /** Default maximum number of retries for a retryable response. See {@link #DEFAULT_CONNECTION_TIMEOUT} for why this is public. */
    public static final int DEFAULT_MAX_RETRY_COUNT = 3;

    /** Default wait, in milliseconds, before the first retry. See {@link #DEFAULT_CONNECTION_TIMEOUT} for why this is public. */
    public static final long DEFAULT_RETRY_INTERVAL = 3000L;

    /** OAuth access token for Slack API access. */
    protected String token;

    /** HTTP proxy configuration for API requests. */
    protected Proxy httpProxy;

    /** Connection timeout in milliseconds. */
    protected int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    /** Read timeout in milliseconds. */
    protected int readTimeout = DEFAULT_READ_TIMEOUT;

    /** Maximum number of retries for a retryable (429/5xx) response. */
    protected int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;

    /** Wait, in milliseconds, before the first retry when no Retry-After header is present. */
    protected long retryInterval = DEFAULT_RETRY_INTERVAL;

    /**
     * Creates a new RequestContext instance with the specified OAuth token.
     *
     * @param token the OAuth access token for Slack API
     */
    public RequestContext(final String token) {
        this.token = token;
    }

    /**
     * Returns the OAuth access token.
     *
     * @return the OAuth access token
     */
    public String getToken() {
        return token;
    }

    /**
     * Configures an HTTP proxy for API requests.
     *
     * @param httpProxyHost the proxy host
     * @param httpProxyPort the proxy port
     */
    public void setHttpProxy(final String httpProxyHost, final Integer httpProxyPort) {
        this.httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, httpProxyPort));
    }

    /**
     * Returns the configured HTTP proxy.
     *
     * @return the HTTP proxy, or null if not configured
     */
    public Proxy getHttpProxy() {
        return httpProxy;
    }

    /**
     * Returns the connection timeout.
     *
     * @return the connection timeout in milliseconds
     */
    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Returns the read timeout.
     *
     * @return the read timeout in milliseconds
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Configures the connection and read timeouts for API requests.
     *
     * @param connectionTimeout the connection timeout in milliseconds
     * @param readTimeout the read timeout in milliseconds
     */
    public void setTimeouts(final int connectionTimeout, final int readTimeout) {
        this.connectionTimeout = connectionTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Returns the maximum number of retries for a retryable response.
     *
     * @return the maximum retry count
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /**
     * Returns the wait, in milliseconds, before the first retry when no
     * {@code Retry-After} header is present.
     *
     * @return the retry interval in milliseconds
     */
    public long getRetryInterval() {
        return retryInterval;
    }

    /**
     * Configures retry behaviour for retryable (429/5xx) responses.
     *
     * @param maxRetryCount the maximum number of retries
     * @param retryInterval the wait, in milliseconds, before the first retry when no
     *            {@code Retry-After} header is present
     */
    public void setRetry(final int maxRetryCount, final long retryInterval) {
        this.maxRetryCount = maxRetryCount;
        this.retryInterval = retryInterval;
    }

}
