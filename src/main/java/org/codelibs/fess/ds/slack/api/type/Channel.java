/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
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
package org.codelibs.fess.ds.slack.api.type;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class Channel {

    protected String id;
    protected String name;
    protected Boolean isChannel;
    protected Boolean isArchived;
    protected Boolean isPrivate;

    protected List<String> members;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Boolean isChannel() {
        return isChannel;
    }

    public Boolean isArchived() {
        return isArchived;
    }

    public Boolean isPrivate() {
        return isPrivate;
    }

    public List<String> getMembers() {
        return members;
    }

}
