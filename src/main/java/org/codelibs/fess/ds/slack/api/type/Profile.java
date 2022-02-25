/*
 * Copyright 2012-2022 CodeLibs Project and the Others.
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class Profile {

    protected String realName;
    protected String displayName;
    protected String email;

    @JsonProperty("image_24")
    protected String image24;

    @JsonProperty("image_32")
    protected String image32;

    @JsonProperty("image_48")
    protected String image48;

    @JsonProperty("image_72")
    protected String image72;

    @JsonProperty("image_192")
    protected String image192;

    @JsonProperty("image_512")
    protected String image512;

    public String getRealName() {
        return realName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getImage24() {
        return image24;
    }

    public String getImage32() {
        return image32;
    }

    public String getImage48() {
        return image48;
    }

    public String getImage72() {
        return image72;
    }

    public String getImage192() {
        return image192;
    }

    public String getImage512() {
        return image512;
    }

}
