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

import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsHistoryResponse;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsInfoRequest;
import org.codelibs.fess.ds.slack.api.method.conversations.ConversationsInfoResponse;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoRequest;
import org.codelibs.fess.ds.slack.api.method.team.TeamInfoResponse;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Pins the <em>form</em> of the message URL, which is the Fess document id.
 *
 * <p>
 * {@link SlackDataStore#getMessagePermalink} has two branches and they do not
 * produce the same string for the same message. With a {@link Team} it composes
 * {@code https://<domain>.slack.com/archives/<channel>/p<ts>}. Without one it asks
 * {@code chat.getPermalink}, and Slack answers a threaded message with
 * {@code ?thread_ts=..&cid=..} appended -- verified against a live workspace on
 * 2026-09-03, where an unthreaded message matched the composed form byte for byte
 * while a thread parent and a thread reply did not.
 * </p>
 *
 * <p>
 * A crawl reads {@code team.info} once, so the two branches never mix within one
 * crawl. They do differ <em>between</em> crawls of the same workspace if the
 * {@code team:read} scope is added or removed, and because the document id is the
 * URL, every threaded message is then re-indexed under a new id while the old one
 * remains until {@code day.for.cleanup} expires it. Neither form is being changed:
 * composing the thread query string would move every existing document, and
 * stripping it would only shorten links for deployments that are already missing a
 * scope the README lists as always required. This test exists so that either change
 * has to be made deliberately.
 * </p>
 */
public class SlackDataStorePermalinkFormTest extends UnitDsTestCase {

    private SlackDataStore dataStore;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        dataStore = new SlackDataStore();
    }

    /** With a team, the URL is composed and carries no query string, threaded or not. */
    @Test
    public void test_composedFormHasNoThreadQueryString() {
        final Team team = team("acme");
        final Channel channel = channel("C0DEADBEEF");

        assertEquals("https://acme.slack.com/archives/C0DEADBEEF/p1700000000000100",
                dataStore.getMessagePermalink(null, team, channel, message("1700000000.000100", null)));

        // A reply inside a thread composes exactly the same way: thread_ts is not
        // part of the composed URL.
        assertEquals("https://acme.slack.com/archives/C0DEADBEEF/p1700000000000200",
                dataStore.getMessagePermalink(null, team, channel, message("1700000000.000200", "1700000000.000100")));
    }

    /**
     * A permalink already on the message wins over both branches, so a future
     * change to either one cannot silently override what Slack itself supplied.
     */
    @Test
    public void test_messagePermalinkWinsOverComposition() {
        final Message message = firstMessage("{\"ok\":true,\"messages\":[{\"ts\":\"1700000000.000100\","
                + "\"permalink\":\"https://acme.slack.com/archives/C1/p1?thread_ts=2&cid=C1\"}]}");
        assertEquals("https://acme.slack.com/archives/C1/p1?thread_ts=2&cid=C1",
                dataStore.getMessagePermalink(null, team("acme"), channel("C1"), message));
    }

    private Team team(final String domain) {
        return new TeamInfoRequest(null).parseResponse(
                "{\"ok\":true,\"team\":{\"id\":\"T1\",\"name\":\"Acme\",\"domain\":\"" + domain + "\"}}", TeamInfoResponse.class).getTeam();
    }

    private Channel channel(final String id) {
        return new ConversationsInfoRequest(null, id)
                .parseResponse("{\"ok\":true,\"channel\":{\"id\":\"" + id + "\",\"name\":\"general\"}}", ConversationsInfoResponse.class)
                .getChannel();
    }

    private Message message(final String ts, final String threadTs) {
        final String thread = threadTs == null ? "" : ",\"thread_ts\":\"" + threadTs + "\"";
        return firstMessage("{\"ok\":true,\"messages\":[{\"ts\":\"" + ts + "\"" + thread + "}]}");
    }

    private Message firstMessage(final String content) {
        return new ConversationsHistoryRequest(null, null).parseResponse(content, ConversationsHistoryResponse.class).getMessages().get(0);
    }
}
