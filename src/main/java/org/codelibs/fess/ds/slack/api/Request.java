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

import java.io.IOException;
import java.net.Proxy;
import java.util.function.Function;

import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.SlackDataStoreException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Abstract base class for all Slack API requests.
 * Provides common functionality for authentication, HTTP request building,
 * and response parsing for Slack Web API calls.
 *
 * @param <T> the response type that this request will return
 */
public abstract class Request<T extends Response> {

    /** Function for creating GET HTTP requests */
    public static final Function<String, CurlRequest> GET = Curl::get;
    /** Function for creating POST HTTP requests */
    public static final Function<String, CurlRequest> POST = Curl::post;
    /** Function for creating PUT HTTP requests */
    public static final Function<String, CurlRequest> PUT = Curl::put;
    /** Function for creating DELETE HTTP requests */
    public static final Function<String, CurlRequest> DELETE = Curl::delete;

    /** Base URL for all Slack API endpoints */
    protected static final String SLACK_API_ENDPOINT = "https://slack.com/api/";
    /** Jackson ObjectMapper for JSON parsing */
    protected static final ObjectMapper mapper = new ObjectMapper();

    /** Authentication credentials for Slack API access */
    protected Authentication authentication;

    /**
     * Constructs a new request with the specified authentication credentials.
     *
     * @param authentication the authentication credentials for Slack API access
     */
    public Request(final Authentication authentication) {
        this.authentication = authentication;
    }

    /**
     * Executes this request and returns the parsed response.
     * Subclasses must implement this method to define the specific API call behavior.
     *
     * @return the parsed response from the Slack API
     */
    public abstract T execute();

    /**
     * Parses the raw JSON response content into the specified response type.
     *
     * @param content the raw JSON response content from the API
     * @param valueType the class type to parse the response into
     * @return the parsed response object
     * @throws SlackDataStoreException if JSON parsing fails
     */
    public T parseResponse(final String content, final Class<T> valueType) {
        try {
            return mapper.readValue(content, valueType).responseBody(content);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + content + "\"", e);
        }
    }

    /**
     * Creates a configured HTTP request for the specified API method and path.
     * Automatically adds authentication headers and proxy configuration.
     *
     * @param method the HTTP method function (GET, POST, PUT, DELETE)
     * @param path the API endpoint path to append to the base URL
     * @return a configured CurlRequest ready for execution
     */
    public CurlRequest getCurlRequest(final Function<String, CurlRequest> method, final String path) {
        final StringBuilder buf = new StringBuilder(100);
        buf.append(SLACK_API_ENDPOINT);
        if (path != null) {
            buf.append(path);
        }
        final CurlRequest request = method.apply(buf.toString()).header("Authorization", "Bearer " + authentication.getToken());
        final Proxy httpProxy = authentication.getHttpProxy();
        if (httpProxy != null) {
            request.proxy(httpProxy);
        }
        return request;
    }
}
