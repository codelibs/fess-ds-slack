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
import org.codelibs.core.exception.InterruptedRuntimeException;
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
     * Upper bound, in milliseconds, on the wait before a retry, regardless of
     * whether it came from a {@code Retry-After} header or the exponential
     * backoff. Slack's {@code Retry-After} can legitimately exceed 30
     * seconds, so a header value is honoured up to this cap rather than
     * clamped to something smaller; a missing or malformed header must still
     * never produce an unbounded wait.
     */
    protected static final long MAX_RETRY_WAIT_MILLIS = 60000L;

    /** The {@code Retry-After} response header name. */
    protected static final String RETRY_AFTER_HEADER = "Retry-After";

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
     * Executes the given request, retrying on a 429 or 5xx response, parses
     * the JSON body into this request's response type and always releases
     * the underlying connection.
     *
     * <p>
     * This is the single place a subclass calls to run its prepared request,
     * so it is also the single place that decides whether a response is
     * retried. Re-executing the same {@link CurlRequest} instance issues a
     * byte-identical request: params are not re-appended, headers are not
     * duplicated, and the URL is not mutated.
     * </p>
     *
     * <p>
     * <b>Retries are decided from the HTTP status code only.</b> A 200
     * response's parsed {@code ok}/{@code error} body is a separate
     * judgement that belongs to {@code SlackClient}, not this layer.
     * </p>
     *
     * <p>
     * <b>Retry exhaustion is signalled on the returned response, not silently absorbed here.</b>
     * When the final attempt still lands on a retryable status, this parses that attempt's body
     * exactly like any other response but also marks it via {@link Response#retriesExhausted()}.
     * Without that flag, a caller has no way to tell "Slack answered {@code ok:false} on the
     * first try" from "every attempt failed and we gave up" -- both would otherwise look like an
     * ordinary parsed body, and Slack's 429 body in particular ({@code
     * {"ok":false,"error":"ratelimited"}}) has no error code of its own that says "I was
     * retried". {@code SlackClient.handleApiError} reads this flag to fail the crawl on
     * exhaustion regardless of the specific error code, rather than silently skipping a channel
     * or page as if the failure were a normal, permanent {@code ok:false} outcome.
     * </p>
     *
     * @param request the prepared request
     * @param valueType the response class
     * @return the parsed response
     * @throws SlackDataStoreException if closing the response fails
     */
    protected T execute(final CurlRequest request, final Class<T> valueType) {
        final int maxRetryCount = requestContext.getMaxRetryCount();
        for (int attempt = 0;; attempt++) {
            try (final CurlResponse response = request.execute()) {
                final int status = response.getHttpStatusCode();
                if (isRetryableStatus(status)) {
                    if (attempt < maxRetryCount) {
                        sleepBeforeRetry(response, attempt + 1, status);
                        continue;
                    }
                    logger.warn("Exhausted {} {} on a Slack API request; the last response was HTTP {}.", maxRetryCount,
                            maxRetryCount == 1 ? "retry" : "retries", status);
                    return parseResponse(response.getContentAsString(), valueType).retriesExhausted(true);
                }
                return parseResponse(response.getContentAsString(), valueType);
            } catch (final IOException e) {
                throw new SlackDataStoreException("Failed to close the response.", e);
            }
        }
    }

    /**
     * Determines whether an HTTP status code should be retried.
     *
     * <p>
     * Retries 429 (rate limited) and any 5xx server error. {@code
     * fatal_error} is documented as also arising from an over-large page
     * size -- a permanent condition -- which is why this alone never decides
     * to retry without the caller also bounding the attempt count.
     * </p>
     *
     * @param status the HTTP status code
     * @return true if the status code should be retried
     */
    protected boolean isRetryableStatus(final int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * Waits before retrying a request and logs the retry so a slower crawl
     * has a visible cause instead of an unexplained one.
     *
     * @param response the response that triggered the retry
     * @param attempt the 1-based retry attempt number, for logging
     * @param status the HTTP status code that triggered the retry, for logging
     */
    protected void sleepBeforeRetry(final CurlResponse response, final int attempt, final int status) {
        final long waitMillis = Math.min(getRetryWaitMillis(response, attempt), MAX_RETRY_WAIT_MILLIS);
        logger.warn("Retrying a Slack API request (attempt {}) after HTTP {}; waiting {} ms.", attempt, status, waitMillis);
        try {
            Thread.sleep(waitMillis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntimeException(e);
        }
    }

    /**
     * Determines the wait, in milliseconds, before the given retry attempt,
     * not yet capped by {@link #MAX_RETRY_WAIT_MILLIS}.
     *
     * <p>
     * Honours the response's {@code Retry-After} header (in seconds) when
     * present and numeric. {@code Retry-After} can instead be an RFC 1123
     * date, which is not a number of seconds; a non-numeric value falls back
     * to an exponential backoff from
     * {@link RequestContext#getRetryInterval()} rather than failing the
     * request.
     * </p>
     *
     * @param response the response that triggered the retry
     * @param attempt the 1-based retry attempt number
     * @return the wait in milliseconds, not yet capped
     */
    protected long getRetryWaitMillis(final CurlResponse response, final int attempt) {
        final String retryAfter = response.getHeaderValue(RETRY_AFTER_HEADER);
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter.trim()) * 1000L;
            } catch (final NumberFormatException e) {
                // Not a number of seconds (e.g. an RFC 1123 date); fall back to the backoff below.
            }
        }
        // The exponent is capped well below where it could overflow: 2^20 times any
        // realistic retry_interval already dwarfs MAX_RETRY_WAIT_MILLIS, so an
        // unusually large max_retry_count cannot turn this into a negative (and
        // therefore Thread.sleep-rejected) wait.
        final int shift = Math.min(attempt - 1, 20);
        return requestContext.getRetryInterval() * (1L << shift);
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
