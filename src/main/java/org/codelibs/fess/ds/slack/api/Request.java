/*
 * Copyright 2012-2024 CodeLibs Project and the Others.
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

public abstract class Request<T extends Response> {

    public static final Function<String, CurlRequest> GET = Curl::get;
    public static final Function<String, CurlRequest> POST = Curl::post;
    public static final Function<String, CurlRequest> PUT = Curl::put;
    public static final Function<String, CurlRequest> DELETE = Curl::delete;

    protected static final String SLACK_API_ENDPOINT = "https://slack.com/api/";
    protected static final ObjectMapper mapper = new ObjectMapper();

    protected Authentication authentication;

    public Request(final Authentication authentication) {
        this.authentication = authentication;
    }

    public abstract T execute();

    public T parseResponse(final String content, final Class<T> valueType) {
        try {
            return mapper.readValue(content, valueType).responseBody(content);
        } catch (final IOException e) {
            throw new SlackDataStoreException("Failed to parse: \"" + content + "\"", e);
        }
    }

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
