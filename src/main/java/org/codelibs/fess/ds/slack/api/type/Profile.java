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
package org.codelibs.fess.ds.slack.api.type;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Represents a user profile in Slack with personal information and avatar images.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Profile {

    /**
     * Default constructor.
     */
    public Profile() {
    }

    /** The user's real name. */
    protected String realName;
    /** The user's display name. */
    protected String displayName;
    /** The user's email address. */
    protected String email;

    /** URL of the user's 24x24 pixel avatar image. */
    @JsonProperty("image_24")
    protected String image24;

    /** URL of the user's 32x32 pixel avatar image. */
    @JsonProperty("image_32")
    protected String image32;

    /** URL of the user's 48x48 pixel avatar image. */
    @JsonProperty("image_48")
    protected String image48;

    /** URL of the user's 72x72 pixel avatar image. */
    @JsonProperty("image_72")
    protected String image72;

    /** URL of the user's 192x192 pixel avatar image. */
    @JsonProperty("image_192")
    protected String image192;

    /** URL of the user's 512x512 pixel avatar image. */
    @JsonProperty("image_512")
    protected String image512;

    /**
     * Returns the user's real name.
     *
     * @return the real name
     */
    public String getRealName() {
        return realName;
    }

    /**
     * Returns the user's display name.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the user's email address.
     *
     * @return the email address
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the URL of the user's 24x24 pixel avatar image.
     *
     * @return the 24x24 image URL
     */
    public String getImage24() {
        return image24;
    }

    /**
     * Returns the URL of the user's 32x32 pixel avatar image.
     *
     * @return the 32x32 image URL
     */
    public String getImage32() {
        return image32;
    }

    /**
     * Returns the URL of the user's 48x48 pixel avatar image.
     *
     * @return the 48x48 image URL
     */
    public String getImage48() {
        return image48;
    }

    /**
     * Returns the URL of the user's 72x72 pixel avatar image.
     *
     * @return the 72x72 image URL
     */
    public String getImage72() {
        return image72;
    }

    /**
     * Returns the URL of the user's 192x192 pixel avatar image.
     *
     * @return the 192x192 image URL
     */
    public String getImage192() {
        return image192;
    }

    /**
     * Returns the URL of the user's 512x512 pixel avatar image.
     *
     * @return the 512x512 image URL
     */
    public String getImage512() {
        return image512;
    }

}
