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

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public abstract class Response {

    protected Boolean ok;
    protected String error;
    protected String responseBody;

    public boolean ok() {
        return ok == null ? false : ok;
    }

    public Boolean getOk() {
        return ok;
    }

    public String getError() {
        return error;
    }

    public String responseBody() {
        return responseBody;
    }

    public <T extends Response> T responseBody(final String responseBody) {
        this.responseBody = responseBody;
        return (T) this;
    }

}
