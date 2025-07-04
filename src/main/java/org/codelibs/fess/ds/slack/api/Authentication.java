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

public class Authentication {

    protected String token;

    protected Proxy httpProxy;

    public Authentication(final String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setHttpProxy(final String httpProxyHost, final Integer httpProxyPort) {
        this.httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, httpProxyPort));
    }

    public Proxy getHttpProxy() {
        return httpProxy;
    }

}
