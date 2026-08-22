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

import org.codelibs.fess.ds.slack.api.Request;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Slack Web API, backed by the JDK's built-in HTTP
 * server. Responses are queued per path and consumed in order; a path with an
 * empty queue answers {@link #DEFAULT_RESPONSE_BODY} so that incidental calls,
 * such as the user and channel preload in the {@link SlackClient} constructor,
 * do not have to be scripted by every test.
 */
public class SlackApiMockServer {

    /**
     * The body returned for a path with no queued response.
     *
     * <p>
     * A bare {@code {"ok":true}} is not enough: {@link SlackClient}'s
     * constructor unconditionally preloads users and channels, and its
     * paging loops call {@code getMembers()}/{@code getChannels()} and
     * {@code getResponseMetadata()} without a null check, so a body missing
     * those fields throws a {@link NullPointerException} before a single test
     * runs. This body carries every field any list/paging response class
     * looks at &mdash; {@code members}, {@code channels}, {@code messages},
     * {@code files}, {@code response_metadata.next_cursor} and {@code paging}
     * &mdash; so it satisfies every endpoint. Every response class is
     * annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so the
     * extra fields a particular endpoint does not use are silently ignored.
     * Do not "simplify" this back to {@code {"ok":true}}.
     */
    static final String DEFAULT_RESPONSE_BODY = "{\"ok\":true,\"members\":[],\"channels\":[],\"messages\":[],\"files\":[],"
            + "\"response_metadata\":{\"next_cursor\":\"\"},\"paging\":{\"count\":0,\"total\":0,\"page\":1,\"pages\":1}}";

    private HttpServer server;

    private final Map<String, List<MockResponse>> queued = new ConcurrentHashMap<>();

    private final Map<String, List<Map<String, String>>> received = new ConcurrentHashMap<>();

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
        final Map<String, String> headers = new HashMap<>();
        headers.put("Retry-After", Integer.toString(retryAfterSeconds));
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
     * Stops the server and restores the production endpoint.
     */
    public void stop() {
        Request.resetEndpoint();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Returns the base URL this server is listening on.
     *
     * @return the endpoint URL, ending with a slash
     */
    public String getEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/";
    }

    /**
     * Queues one response for the given path. Responses are consumed in order.
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
     * Returns the query parameters of each request made to the given path, in
     * arrival order.
     *
     * @param path the request path
     * @return the recorded query parameter maps
     */
    public List<Map<String, String>> getRequests(final String path) {
        return received.getOrDefault(path, Collections.emptyList());
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        received.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(parseQuery(exchange.getRequestURI().getRawQuery()));

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
            response = json(DEFAULT_RESPONSE_BODY);
        }

        final byte[] payload = response.body.getBytes(StandardCharsets.UTF_8);
        response.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.statusCode, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
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
