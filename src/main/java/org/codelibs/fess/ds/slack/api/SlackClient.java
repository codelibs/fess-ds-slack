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
package org.codelibs.fess.ds.slack.api;

import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsClient;
import org.codelibs.fess.ds.slack.api.method.files.FilesClient;
import org.codelibs.fess.ds.slack.api.method.users.UsersClient;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;

public class SlackClient {

    protected final HttpRequestFactory httpRequestFactory;

    public final ConversationsClient conversations;
    public final UsersClient users;
    public final FilesClient files;

    public SlackClient(final String token) {
        this.httpRequestFactory = new NetHttpTransport().createRequestFactory(new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) {
                request.getHeaders().setAuthorization("Bearer " + token);
            }
        });
        this.conversations = new ConversationsClient(this);
        this.users = new UsersClient(this);
        this.files = new FilesClient(this);
    }

    public HttpRequestFactory request() {
        return httpRequestFactory;
    }

    public String endpoint() {
        return "https://slack.com/api/";
    }

}