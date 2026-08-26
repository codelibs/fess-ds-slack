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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.codelibs.fess.ds.slack.api.Request;
import org.codelibs.fess.entity.DataStoreParams;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Slack Web API, backed by the JDK's built-in HTTP
 * server. Responses are queued per path and consumed in order; a path with an
 * empty queue answers {@link #DEFAULT_RESPONSE_BODY} so that incidental calls,
 * such as the user and channel preload in the {@link SlackClient} constructor,
 * do not have to be scripted by every test. Call {@link #setStrict(boolean)}
 * to turn that convenience off and make an unscripted request fail loudly
 * instead of quietly succeeding.
 *
 * <p>
 * <b>{@link SlackClient}'s constructor consumes one queued response each for
 * {@code /api/users.list} and {@code /api/conversations.list}.</b> It
 * unconditionally preloads users and channels before returning, so a response
 * enqueued for either of those two paths before the client is constructed is
 * eaten by that preload rather than by the test's own call &mdash; silently,
 * with no error or warning. Enqueue for those two paths only after the client
 * exists, or budget one extra entry for the preload. Prefer
 * {@link #newClient(DataStoreParams)} (or {@link #newClient(String)}) over
 * calling {@code new SlackClient(...)} directly, so this ordering does not
 * have to be remembered separately by every test.
 * </p>
 */
public class SlackApiMockServer implements AutoCloseable {

    /**
     * The body returned for a path with no queued response.
     *
     * <p>
     * This body satisfies list/paging endpoints only &mdash;
     * {@code users.list}, {@code conversations.list},
     * {@code conversations.history}, {@code conversations.replies} and
     * {@code files.list}. A bare {@code {"ok":true}} is not enough even for
     * those: {@link SlackClient}'s constructor unconditionally preloads users
     * and channels, and its paging loops call
     * {@code getMembers()}/{@code getChannels()} and
     * {@code getResponseMetadata()} without a null check, so a body missing
     * those fields throws a {@link NullPointerException} before a single test
     * runs. This body carries every field any list/paging response class
     * looks at &mdash; {@code members}, {@code channels}, {@code messages},
     * {@code files}, {@code response_metadata.next_cursor} and {@code paging}
     * &mdash; so those endpoints get a well-formed, empty page. Every
     * response class is annotated {@code @JsonIgnoreProperties(ignoreUnknown
     * = true)}, so the extra fields a particular endpoint does not use are
     * silently ignored.
     * </p>
     *
     * <p>
     * <b>It does not satisfy the endpoints whose response carries a single
     * scalar object</b>: {@code team.info}, {@code users.info},
     * {@code conversations.info}, {@code bots.info}, {@code files.info} and
     * {@code chat.getPermalink} all get {@code null} back from their
     * {@code getTeam()}/{@code getUser()}/{@code getChannel()}/
     * {@code getBot()}/{@code getFile()}/{@code getPermalink()} accessor when
     * answered with this body, because it carries no {@code team}/
     * {@code user}/{@code channel}/{@code bot}/{@code file}/
     * {@code permalink} field. Worse, going through one of
     * {@link SlackClient}'s caches (e.g. {@code client.getUser(...)}) turns
     * that {@code null} into a
     * {@code com.google.common.cache.CacheLoader$InvalidCacheLoadException}
     * whose message says nothing about the mock server. Always enqueue an
     * explicit response for those six endpoints; do not rely on this default.
     * </p>
     *
     * Do not "simplify" this back to {@code {"ok":true}}.
     */
    static final String DEFAULT_RESPONSE_BODY = "{\"ok\":true,\"members\":[],\"channels\":[],\"messages\":[],\"files\":[],"
            + "\"response_metadata\":{\"next_cursor\":\"\"},\"paging\":{\"count\":0,\"total\":0,\"page\":1,\"pages\":1}}";

    /** The body returned for an unscripted request while {@link #setStrict(boolean)} is on. */
    static final String UNSCRIPTED_RESPONSE_BODY = "{\"ok\":false,\"error\":\"unscripted_request\"}";

    private HttpServer server;

    private volatile boolean strict;

    private final Map<String, List<MockResponse>> queued = new ConcurrentHashMap<>();

    private final Map<String, List<Map<String, String>>> received = new ConcurrentHashMap<>();

    private final Map<String, List<String>> receivedAuthorizations = new ConcurrentHashMap<>();

    /** A canned HTTP response. */
    public static class MockResponse {
        final int statusCode;
        final String body;
        final Map<String, String> headers;

        MockResponse(final int statusCode, final String body, final Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * Builds an HTTP 200 response carrying the given JSON body.
     *
     * @param body the JSON body
     * @return the response
     */
    public static MockResponse json(final String body) {
        return new MockResponse(200, body, Collections.emptyMap());
    }

    /**
     * Builds an HTTP 429 response carrying a Retry-After header, matching what
     * Slack returns when a method exceeds its rate limit.
     *
     * @param retryAfterSeconds the value of the Retry-After header, in seconds
     * @return the response
     */
    public static MockResponse rateLimited(final int retryAfterSeconds) {
        return rateLimited(Integer.toString(retryAfterSeconds));
    }

    /**
     * Builds an HTTP 429 response carrying a Retry-After header with an arbitrary raw value,
     * for exercising a malformed header (e.g. negative, or too large to fit an {@code int})
     * that {@link #rateLimited(int)} cannot express.
     *
     * @param retryAfterHeaderValue the raw value of the Retry-After header
     * @return the response
     */
    public static MockResponse rateLimited(final String retryAfterHeaderValue) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Retry-After", retryAfterHeaderValue);
        return new MockResponse(429, "{\"ok\":false,\"error\":\"ratelimited\"}", headers);
    }

    /**
     * Builds a response with an arbitrary status code and body.
     *
     * @param code the HTTP status code
     * @param body the response body
     * @return the response
     */
    public static MockResponse status(final int code, final String body) {
        return new MockResponse(code, body, Collections.emptyMap());
    }

    /**
     * Starts the server on an ephemeral port and points the Slack client at it.
     *
     * @throws IOException if the server cannot bind
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", this::handle);
        server.setExecutor(null);
        server.start();
        Request.setEndpoint(getEndpoint());
    }

    /**
     * Stops the server and restores the production endpoint. Safe to call
     * more than once, and safe to call when the server was never started.
     */
    public void stop() {
        Request.resetEndpoint();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Equivalent to {@link #stop()}, so the server can be used in
     * try-with-resources.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the base URL this server is listening on.
     *
     * @return the endpoint URL, ending with a slash
     * @throws IllegalStateException if the server has not been {@link #start() started}, or has
     *             already been {@link #stop() stopped} &mdash; deliberately, rather than returning
     *             a sentinel URL that would let a caller keep going against a server that is not
     *             actually listening
     */
    public String getEndpoint() {
        final HttpServer current = server;
        if (current == null) {
            throw new IllegalStateException("SlackApiMockServer is not running: call start() first, " + "or it has already been stop()ped");
        }
        return "http://127.0.0.1:" + current.getAddress().getPort() + "/api/";
    }

    /**
     * Constructs a {@link SlackClient} against this server.
     *
     * <p>
     * Prefer this over calling {@code new SlackClient(paramMap)} directly:
     * it puts the one place that has to know about the constructor's
     * users/channels preload (see the class javadoc) in the harness instead
     * of in every test.
     * </p>
     *
     * @param paramMap the configuration parameters, e.g. {@code token}
     * @return a client constructed against this server's endpoint
     */
    public SlackClient newClient(final DataStoreParams paramMap) {
        return new SlackClient(paramMap);
    }

    /**
     * Convenience overload of {@link #newClient(DataStoreParams)} for the
     * common case of only needing to set the OAuth token.
     *
     * @param token the OAuth access token
     * @return a client constructed against this server's endpoint
     */
    public SlackClient newClient(final String token) {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", token);
        return newClient(paramMap);
    }

    /**
     * Constructs a {@link SlackClient} against this server with an explicit {@code
     * aliveSupplier}, for tests that exercise a crawl being stopped mid-walk.
     *
     * @param paramMap the configuration parameters, e.g. {@code token}
     * @param aliveSupplier supplies whether the crawl should keep paging
     * @return a client constructed against this server's endpoint
     */
    public SlackClient newClient(final DataStoreParams paramMap, final BooleanSupplier aliveSupplier) {
        return new SlackClient(paramMap, aliveSupplier);
    }

    /**
     * Convenience overload of {@link #newClient(DataStoreParams, BooleanSupplier)} for the
     * common case of only needing to set the OAuth token.
     *
     * @param token the OAuth access token
     * @param aliveSupplier supplies whether the crawl should keep paging
     * @return a client constructed against this server's endpoint
     */
    public SlackClient newClient(final String token, final BooleanSupplier aliveSupplier) {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", token);
        return newClient(paramMap, aliveSupplier);
    }

    /**
     * Queues one response for the given path. Responses are consumed in order.
     *
     * <p>
     * <b>{@code /api/users.list} and {@code /api/conversations.list} are
     * special:</b> {@link SlackClient}'s constructor consumes one queued
     * response from each of those two paths as part of its preload, before
     * any code the test wrote runs. Enqueue for those two paths only after
     * the client is constructed (see {@link #newClient(DataStoreParams)}), or
     * account for the preload by queuing one extra entry first.
     * </p>
     *
     * @param path the request path, for example <code>/api/team.info</code>
     * @param response the response to return
     */
    public void enqueue(final String path, final MockResponse response) {
        queued.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>())).add(response);
    }

    /**
     * Returns how many times the given path was requested.
     *
     * @param path the request path
     * @return the request count
     */
    public int getRequestCount(final String path) {
        return getRequests(path).size();
    }

    /**
     * Returns how many responses enqueued for the given path have not yet
     * been consumed by a request.
     *
     * @param path the request path
     * @return the number of responses still queued
     */
    public int getQueuedCount(final String path) {
        final List<MockResponse> responses = queued.get(path);
        if (responses == null) {
            return 0;
        }
        synchronized (responses) {
            return responses.size();
        }
    }

    /**
     * Asserts that every response enqueued via {@link #enqueue} was consumed
     * by a request. Call this at the end of a test to catch a test author
     * under-driving the client under test: leftover queue entries mean fewer
     * requests were made than the test scripted for, which otherwise passes
     * silently.
     *
     * @throws AssertionError if any path still has unconsumed responses, naming each such path and
     *             its leftover count
     */
    public void assertAllConsumed() {
        final StringBuilder message = new StringBuilder();
        for (final Map.Entry<String, List<MockResponse>> entry : queued.entrySet()) {
            final int remaining;
            synchronized (entry.getValue()) {
                remaining = entry.getValue().size();
            }
            if (remaining > 0) {
                if (message.length() > 0) {
                    message.append(", ");
                }
                message.append(entry.getKey()).append(" (").append(remaining).append(" left)");
            }
        }
        if (message.length() > 0) {
            throw new AssertionError("Unconsumed queued responses: " + message);
        }
    }

    /**
     * Sets whether an unscripted request &mdash; a path with no queued
     * response left &mdash; is answered with an error instead of
     * {@link #DEFAULT_RESPONSE_BODY}.
     *
     * <p>
     * Off by default, so existing tests that rely on the default body for
     * incidental calls, such as {@link SlackClient}'s constructor preload,
     * keep working unchanged. Turn this on in a test that wants every
     * request scripted explicitly: an unscripted request then gets HTTP 400
     * with body {@value #UNSCRIPTED_RESPONSE_BODY} instead of a quiet empty
     * success, so a missing or exhausted enqueue fails loudly instead of
     * looking like zero results.
     * </p>
     *
     * <p>
     * <b>Deliberately 400, not a 5xx or 429:</b> "you forgot to stub this
     * path" is a test-authoring error, not a transient server condition, so
     * this must stay outside {@code Request}'s retryable range. A 5xx here
     * would make every strict-mode test that goes through {@link SlackClient}
     * pay a real retry backoff for a condition retrying can never fix.
     * </p>
     *
     * @param strict whether unscripted requests should fail loudly
     */
    public void setStrict(final boolean strict) {
        this.strict = strict;
    }

    /**
     * Returns the query parameters of each request made to the given path, in
     * arrival order. The returned list is a snapshot copy; it is safe to
     * iterate even while another thread is recording further requests, and
     * mutating it has no effect on this server.
     *
     * @param path the request path
     * @return the recorded query parameter maps
     */
    public List<Map<String, String>> getRequests(final String path) {
        final List<Map<String, String>> requests = received.get(path);
        if (requests == null) {
            return Collections.emptyList();
        }
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    /**
     * Returns the {@code Authorization} header of each request made to the
     * given path, in arrival order, with one entry per request &mdash;
     * {@code null} for a request that sent no such header. Entries line up
     * by index with {@link #getRequests(String)}, because the server
     * dispatches on a single thread.
     *
     * <p>
     * Recorded separately from the query parameters because the OAuth token
     * travels in this header and nowhere else (see
     * {@code Request#getCurlRequest}), so without this a test has no way to
     * observe whether the token was sent at all: deleting the header
     * outright leaves the whole suite green.
     * </p>
     *
     * @param path the request path
     * @return the recorded header values
     */
    public List<String> getAuthorizations(final String path) {
        final List<String> authorizations = receivedAuthorizations.get(path);
        if (authorizations == null) {
            return Collections.emptyList();
        }
        synchronized (authorizations) {
            return new ArrayList<>(authorizations);
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        received.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(parseQuery(exchange.getRequestURI().getRawQuery()));
        receivedAuthorizations.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(exchange.getRequestHeaders().getFirst("Authorization"));
        drain(exchange.getRequestBody());

        final List<MockResponse> responses = queued.get(path);
        MockResponse response = null;
        if (responses != null) {
            synchronized (responses) {
                if (!responses.isEmpty()) {
                    response = responses.remove(0);
                }
            }
        }
        if (response == null) {
            response = strict ? status(400, UNSCRIPTED_RESPONSE_BODY) : json(DEFAULT_RESPONSE_BODY);
        }

        final byte[] payload = response.body.getBytes(StandardCharsets.UTF_8);
        response.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.statusCode, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * Reads and discards the remainder of the request body. Every Slack call
     * this harness serves today is a GET with no body, so this is a no-op in
     * practice, but {@link HttpExchange} requires the request body to be
     * fully consumed before the response is sent; skipping it would silently
     * break keep-alive the moment a POST request is added.
     */
    private void drain(final InputStream in) throws IOException {
        final byte[] buf = new byte[1024];
        while (in.read(buf) != -1) {
            // discard
        }
    }

    private Map<String, String> parseQuery(final String rawQuery) {
        final Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (final String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
