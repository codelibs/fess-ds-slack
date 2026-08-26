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
 * credentials, proxy settings, and connection/read timeouts. It carries more
 * than authentication, so it lives under a name that does not imply
 * otherwise.
 */
public class RequestContext {

    /** Default connection timeout in milliseconds. */
    protected static final int DEFAULT_CONNECTION_TIMEOUT = 20000;

    /** Default read timeout in milliseconds. */
    protected static final int DEFAULT_READ_TIMEOUT = 20000;

    /** OAuth access token for Slack API access. */
    protected String token;

    /** HTTP proxy configuration for API requests. */
    protected Proxy httpProxy;

    /** Connection timeout in milliseconds. */
    protected int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    /** Read timeout in milliseconds. */
    protected int readTimeout = DEFAULT_READ_TIMEOUT;

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

}
