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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlRequest;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.ds.slack.SlackDataStoreException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Abstract base class for all Slack API requests.
 * Provides common functionality for the request context, HTTP request building,
 * and response parsing for Slack Web API calls.
 *
 * @param <T> the response type that this request will return
 */
public abstract class Request<T extends Response> {

    private static final Logger logger = LogManager.getLogger(Request.class);

    /** Function for creating GET HTTP requests */
    public static final Function<String, CurlRequest> GET = Curl::get;
    /** Function for creating POST HTTP requests */
    public static final Function<String, CurlRequest> POST = Curl::post;
    /** Function for creating PUT HTTP requests */
    public static final Function<String, CurlRequest> PUT = Curl::put;
    /** Function for creating DELETE HTTP requests */
    public static final Function<String, CurlRequest> DELETE = Curl::delete;

    /** The default Slack Web API endpoint. */
    protected static final String DEFAULT_SLACK_API_ENDPOINT = "https://slack.com/api/";

    /** The Slack Web API endpoint in effect. Overridable for testing. */
    private static volatile String slackApiEndpoint = DEFAULT_SLACK_API_ENDPOINT;

    /** Jackson ObjectMapper for JSON parsing */
    protected static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns the Slack Web API endpoint currently in effect.
     *
     * @return the endpoint URL, always ending with a slash
     */
    protected static String getEndpoint() {
        return slackApiEndpoint;
    }

    /**
     * Overrides the Slack Web API endpoint. Intended for testing against a local
     * stub server; production code must not call this.
     *
     * @param endpoint the endpoint URL, which must end with a slash
     * @throws IllegalArgumentException if {@code endpoint} is {@code null} or does not end with
     *             a slash; either would otherwise silently concatenate into a malformed URL
     *             such as {@code "nullteam.info"} or {@code ".../apiteam.info"}
     */
    public static void setEndpoint(final String endpoint) {
        if (endpoint == null || !endpoint.endsWith("/")) {
            throw new IllegalArgumentException("endpoint must be non-null and end with '/': " + endpoint);
        }
        slackApiEndpoint = endpoint;
    }

    /**
     * Restores the Slack Web API endpoint to {@link #DEFAULT_SLACK_API_ENDPOINT}.
     */
    public static void resetEndpoint() {
        slackApiEndpoint = DEFAULT_SLACK_API_ENDPOINT;
    }

    /** Request context for Slack API access */
    protected RequestContext requestContext;

    /**
     * Constructs a new request with the specified request context.
     *
     * @param requestContext the request context for Slack API access
     */
    public Request(final RequestContext requestContext) {
        this.requestContext = requestContext;
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
     * <p>
     * The response body is not included in the exception message: an invalid
     * token can make Slack answer with an HTML error page, and any response
     * body may carry PII, so embedding it verbatim would let it reach the
     * log at whatever level the caller logs the exception. The body is
     * logged separately, at debug level only.
     * </p>
     *
     * <p>
     * The catch is {@link Exception}, not {@link IOException}: {@code
     * ObjectMapper.readValue((String) null, ...)} throws {@link
     * IllegalArgumentException}, which is not an {@code IOException} and would
     * otherwise escape uncaught -- as would a null {@code valueType} or any
     * other unchecked failure from Jackson -- defeating the point of wrapping
     * every parse failure in a single, well-formed {@link SlackDataStoreException}.
     * </p>
     *
     * @param content the raw JSON response content from the API
     * @param valueType the class type to parse the response into
     * @return the parsed response object
     * @throws SlackDataStoreException if JSON parsing fails
     */
    public T parseResponse(final String content, final Class<T> valueType) {
        try {
            return mapper.readValue(content, valueType).responseBody(content);
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unparseable Slack API response: {}", content);
            }
            throw new SlackDataStoreException(
                    "Failed to parse a Slack API response of " + (content == null ? 0 : content.length()) + " characters.", e);
        }
    }

    /**
     * Executes the given request, parses the JSON body into this request's
     * response type and always releases the underlying connection.
     *
     * <p>
     * This is the single place a subclass calls to run its prepared request,
     * so it is also the single place a future retry layer needs to change to
     * read {@link CurlResponse#getHttpStatusCode()} or a
     * {@code Retry-After} header before the response is parsed and closed.
     * </p>
     *
     * @param request the prepared request
     * @param valueType the response class
     * @return the parsed response
     * @throws SlackDataStoreException if closing the response fails
     */
    protected T execute(final CurlRequest request, final Class<T> valueType) {
        try (final CurlResponse response = request.execute()) {
            return parseResponse(response.getContentAsString(), valueType);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to close the response.", e);
        }
    }

    /**
     * Creates a configured HTTP request for the specified API method and path.
     * Automatically adds the authentication header, proxy configuration, and
     * connection/read timeouts from the request context.
     *
     * @param method the HTTP method function (GET, POST, PUT, DELETE)
     * @param path the API endpoint path to append to the base URL
     * @return a configured CurlRequest ready for execution
     */
    public CurlRequest getCurlRequest(final Function<String, CurlRequest> method, final String path) {
        final StringBuilder buf = new StringBuilder(100);
        buf.append(getEndpoint());
        if (path != null) {
            buf.append(path);
        }
        final CurlRequest request = method.apply(buf.toString()).header("Authorization", "Bearer " + requestContext.getToken());
        final Proxy httpProxy = requestContext.getHttpProxy();
        if (httpProxy != null) {
            request.proxy(httpProxy);
        }
        request.timeout(requestContext.getConnectionTimeout(), requestContext.getReadTimeout());
        return request;
    }
}
