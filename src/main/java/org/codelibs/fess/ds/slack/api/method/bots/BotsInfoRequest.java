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
package org.codelibs.fess.ds.slack.api.method.bots;

import org.codelibs.curl.CurlRequest;
import org.codelibs.fess.ds.slack.api.Authentication;
import org.codelibs.fess.ds.slack.api.Request;

/**
 * Request to retrieve information about a specific bot in Slack.
 */
public class BotsInfoRequest extends Request<BotsInfoResponse> {

    /** The bot ID to retrieve information for. */
    protected String bot;

    /**
     * Creates a new bots.info request with the specified authentication.
     *
     * @param authenctication the authentication credentials
     */
    public BotsInfoRequest(final Authentication authenctication) {
        super(authenctication);
    }

    @Override
    public BotsInfoResponse execute() {
        return parseResponse(request().execute().getContentAsString(), BotsInfoResponse.class);
    }

    /**
     * Sets the bot ID to retrieve information for.
     *
     * @param bot the bot ID
     * @return this request instance for method chaining
     */
    public BotsInfoRequest bot(final String bot) {
        this.bot = bot;
        return this;
    }

    private CurlRequest request() {
        final CurlRequest request = getCurlRequest(GET, "bots.info");
        if (bot != null) {
            request.param("bot", bot);
        }
        return request;
    }

}
