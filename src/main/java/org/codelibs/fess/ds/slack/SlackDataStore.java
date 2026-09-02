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

import static java.util.Collections.EMPTY_LIST;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.core.stream.StreamUtil;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MaxLengthExceededException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.slack.api.SlackApiException;
import org.codelibs.fess.ds.slack.api.type.Attachment;
import org.codelibs.fess.ds.slack.api.type.Channel;
import org.codelibs.fess.ds.slack.api.type.File;
import org.codelibs.fess.ds.slack.api.type.Message;
import org.codelibs.fess.ds.slack.api.type.Profile;
import org.codelibs.fess.ds.slack.api.type.Team;
import org.codelibs.fess.ds.slack.api.type.User;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.helper.PermissionHelper;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.lastaflute.di.core.exception.ComponentNotFoundException;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * Slack Data Store implementation that enables Fess to crawl and index Slack content
 * including channels, messages, and files. This data store connects to the Slack API
 * to retrieve content and makes it searchable within Fess.
 *
 * <p>Supported content types:</p>
 * <ul>
 * <li>Channel messages with threading support</li>
 * <li>File attachments with content extraction</li>
 * <li>Bot messages and user posts</li>
 * <li>Public and private channels (configurable)</li>
 * </ul>
 *
 * <p>Configuration parameters:</p>
 * <ul>
 * <li>token: OAuth access token for Slack API</li>
 * <li>channels: Specific channels to crawl or "*all" for all channels</li>
 * <li>include_private: Whether to include private channels</li>
 * <li>file_crawl: Whether to crawl file attachments</li>
 * <li>number_of_threads: Thread pool size for parallel processing</li>
 * </ul>
 *
 * <h2>What happens when a Slack API call fails</h2>
 * <p>
 * Four mechanisms cooperate, each documented in full where it lives; this is only the map of how
 * they hand off to each other, since no single one of their javadocs states the whole path:
 * </p>
 * <ol>
 * <li><b>Retry-on-status</b> ({@code org.codelibs.fess.ds.slack.api.Request#execute}): a 429 or
 * 5xx HTTP status is retried up to {@code max_retry_count} times, honouring a {@code Retry-After}
 * header when present. If every attempt still lands on a retryable status, the last attempt's
 * body is parsed and returned like any other response, but flagged via
 * {@link org.codelibs.fess.ds.slack.api.Response#retriesExhausted()} -- see that method's
 * javadoc for why this layer does not decide fatal-vs-skip itself.</li>
 * <li><b>Body classification</b> ({@link SlackClient#handleApiError}): every paginated call
 * (files.list, conversations.list, conversations.history, conversations.replies,
 * conversations.members, users.list) and every single-object lookup (users.info, bots.info,
 * conversations.info, chat.getPermalink) routes its {@code ok:false} body here. A code in
 * {@link SlackClient#FATAL_ERROR_CODES} (the token itself cannot authenticate) or
 * {@link SlackClient#TRANSIENT_ERROR_CODES} (a transient server-side condition, not a property
 * of the specific channel/page), or a response with {@code retriesExhausted()} set regardless of
 * its code, throws {@link org.codelibs.fess.ds.slack.api.SlackApiException}. Everything else is
 * warned and skipped for just that channel/page. A lookup's "not found" (e.g. {@code
 * user_not_found}) stays in that skip bucket on purpose: only then does the caller's fall back to
 * the raw ID remain the right answer.</li>
 * <li><b>Propagation or worker latching</b>: a {@link org.codelibs.fess.ds.slack.api.SlackApiException}
 * thrown on {@link #storeData}'s own thread (files.list, conversations.list,
 * conversations.history, users.list, the constructor's preload of both listing calls, and
 * conversations.members and users.info -- called from {@link #computeChannelRoles}/{@link
 * #getMemberEmail}, themselves invoked synchronously from the channel-walk callback passed to
 * {@code client.getChannels}, never from a worker thread) propagates directly out of
 * {@link #storeData}. One raised on a worker thread dispatched by
 * {@link #processChannelMessages}/{@link #processChannelFiles} (conversations.replies via {@link
 * #processMessageReplies}, and users.info/bots.info/chat.getPermalink via {@link #processMessage}
 * and {@link #processFile}) cannot reach the submitting thread that way -- {@link
 * java.util.concurrent.ThreadPoolExecutor} swallows an uncaught exception from its {@code
 * Runnable} -- so it is caught there and handed to {@link #latchFatalError} instead, which
 * records it and stops the crawl (next mechanism) rather than letting it vanish.</li>
 * <li><b>Two flags for stopping</b>: an operator's admin-UI "stop" flips the inherited {@code
 * alive} flag, which Fess assigns {@code true} once at field initialisation and never resets;
 * {@link #latchFatalError} and an aborting {@link #resolveFailureUrl} instead clear this crawl's
 * own {@link #CRAWL_ALIVE} flag, deliberately <em>not</em> {@code alive}, because a data store is
 * a LastaDi singleton reused by every later crawl (see {@link #latchFatalError}). Both are
 * honoured together: every paging loop in {@link SlackClient} consults a supplier over the pair,
 * and {@link #storeData}'s own channel-dispatch loop checks both before dispatching the next
 * channel. {@link #storeData} tolerates both landing at once: an admin-UI stop with no latched
 * error logs that the crawl was cut short; a latched error takes precedence and is (re)thrown
 * once the executor has drained, which is the more actionable of the two for an operator to
 * see.</li>
 * </ol>
 */
public class SlackDataStore extends AbstractDataStore {

    private static final Logger logger = LogManager.getLogger(SlackDataStore.class);

    /**
     * Default constructor for SlackDataStore.
     */
    public SlackDataStore() {
        super();
    }

    /** Default maximum file size for processing (10MB). */
    protected static final long DEFAULT_MAX_FILESIZE = 10000000L; // 10m

    /**
     * Default value of {@link #MAX_CONTENT_LENGTH}: defer to {@link
     * org.codelibs.fess.crawler.helper.ContentLengthHelper}'s per-MIME-type limit, the same
     * fallback {@code ExtractorBuilder#extract()} already applies on its own {@code
     * maxContentLength} field when nothing overrides it. This mirrors {@code
     * OneDriveDataStore}'s {@code DEFAULT_MAX_SIZE} convention (fess-ds-microsoft365).
     */
    protected static final long DEFAULT_MAX_CONTENT_LENGTH = -1L;

    // parameters
    /** Parameter name for ignoring errors during crawling. */
    protected static final String IGNORE_ERROR = "ignore_error";
    /** Parameter name for supported MIME types. */
    protected static final String SUPPORTED_MIMETYPES = "supported_mimetypes";
    /** Parameter name for URL include patterns. */
    protected static final String INCLUDE_PATTERN = "include_pattern";
    /** Parameter name for URL exclude patterns. */
    protected static final String EXCLUDE_PATTERN = "exclude_pattern";
    /** Parameter name for URL filter configuration. */
    protected static final String URL_FILTER = "url_filter";
    /**
     * {@code configMap} key holding this crawl's stop flag, an {@link AtomicBoolean}.
     *
     * <p>
     * Not a user-settable parameter: {@code storeData} puts it there so the per-message and
     * per-file paths can reach it without adding another argument to six already-long
     * signatures, the same way {@link #URL_FILTER} carries a resolved component rather than a
     * setting.
     * </p>
     */
    protected static final String CRAWL_ALIVE = "crawl_alive";
    /** Parameter name for thread pool size. */
    protected static final String NUMBER_OF_THREADS = "number_of_threads";
    /**
     * Parameter name for the maximum file size, in bytes, this data store will download.
     *
     * <p>
     * This is a <b>transfer bound</b>: {@link #processFile} checks it against {@code
     * file.getSize()} -- the size Slack's API reports in file metadata -- <i>before</i>
     * downloading anything, and skips the download entirely (throwing {@code
     * MaxLengthExceededException}) when the file is too big. It says nothing about how much of
     * what gets downloaded is then allowed to weigh; see {@link #MAX_CONTENT_LENGTH} for that
     * bound. The two differ in <i>what</i> they measure rather than in kind: this one trusts the
     * size Slack reports, its sibling measures the bytes actually received, so a file whose
     * metadata understates it is still caught -- but neither bounds extracted text, and a
     * generous {@code max_content_length} does not widen what this parameter lets through the
     * network call in the first place.
     * </p>
     */
    protected static final String MAX_FILESIZE = "max_filesize";
    /**
     * Parameter name for the maximum size, in bytes, a downloaded file may have before this
     * data store refuses to extract text from it.
     *
     * <p>
     * A <b>post-download size bound</b>, not a truncation limit. It is passed to {@code
     * ExtractorBuilder#maxContentLength(long)}, which compares it against the byte count of the
     * downloaded stream and throws {@code MaxLengthExceededException} when the file is larger --
     * <i>before</i> handing anything to Tika. Nothing truncates: an over-size file is skipped
     * whole, and the value never reaches the extractor. {@code OneDriveDataStore}
     * (fess-ds-microsoft365) uses the same parameter the same way, as its file-size bound.
     * </p>
     *
     * <p>
     * It therefore overlaps {@link #MAX_FILESIZE} rather than complementing it. What it adds is
     * a check on the bytes actually received, after the transfer, where {@code max_filesize}
     * trusts the size Slack's metadata reports before the transfer.
     * </p>
     *
     * <p>
     * Unset (or a negative value) means {@link #DEFAULT_MAX_CONTENT_LENGTH}: defer to {@code
     * ContentLengthHelper}'s per-MIME-type limit. That fallback is not new behavior this
     * parameter turns on -- {@code ExtractorBuilder#extract()} already runs it internally
     * whenever nothing else set a non-negative {@code maxContentLength}, which was already true
     * of every call this data store made before this parameter existed. What this parameter adds
     * is the ability for an operator to override that per-MIME default with an explicit,
     * possibly stricter, limit; leaving it unset changes nothing about current behavior.
     * </p>
     */
    protected static final String MAX_CONTENT_LENGTH = "max_content_length";
    /** Parameter name for enabling file crawling. */
    protected static final String FILE_CRAWL = "file_crawl";
    /** Regular expression pattern that matches any MIME type; the default of {@link #SUPPORTED_MIMETYPES}. */
    protected static final String MATCH_ALL_MIMETYPES = ".*";
    /** Parameter name for how long, in seconds, to wait for queued crawl work to finish before forcing shutdown. */
    protected static final String EXECUTOR_TIMEOUT = "executor_timeout";
    /** Default number of seconds {@link #storeData} waits for queued crawl work to finish before forcing shutdown. */
    protected static final int DEFAULT_EXECUTOR_TIMEOUT = 60;
    /** Parameter name for excluding Slack-generated channel-administration messages. */
    protected static final String IGNORE_SYSTEM_EVENTS = "ignore_system_events";
    /**
     * Parameter name for the delay, in milliseconds, to sleep after each processed message/file.
     * Read by the inherited {@link AbstractDataStore#getReadInterval}, not by a method in this
     * class: that method's own key, {@code "readInterval"}, and this one are the same key to
     * {@link org.codelibs.fess.entity.ParamMap} -- its lookup falls back to the other case
     * convention on a miss -- so no override is needed here.
     */
    protected static final String READ_INTERVAL = "read_interval";
    /**
     * Parameter name for enabling ACL synchronisation: resolving each private channel's
     * membership into search roles and exposing them to the crawl script as {@link
     * #MESSAGE_ROLES}.
     *
     * <p>
     * <b>Not "searchable only by that channel's members".</b> The member roles are <em>added</em>
     * to {@link #DEFAULT_PERMISSIONS} and to this crawl's DataConfig permission (see {@link
     * #mergeAdditionalRoles}), and a Fess document is visible to anyone holding any one of its
     * roles -- so a private channel's content is searchable by its members <em>plus</em> whoever
     * those two sources name. That is the additive convention {@code
     * Microsoft365DataStore#mergeDefaultRoles} sets for {@code OneDriveDataStore} and {@code
     * TeamsDataStore} (fess-ds-microsoft365), and dropping the DataConfig source would silently
     * discard an operator's admin-UI permissions (F15). It does mean the admin UI's pre-filled
     * {@code {role}guest} defeats this parameter unless the operator clears that field; see this
     * module's README.
     * </p>
     *
     * <p>
     * Defaults to {@code false} (opt-in). Turning this on for a workspace with content already
     * indexed under the previous, unrestricted behavior makes every private channel's documents
     * suddenly invisible in search until the next crawl re-indexes them with roles attached --
     * a significant, surprising change in what search results a user sees, so it is not the
     * default. See {@link #isPermissionSync} and this class's fail-closed handling in {@link
     * #computeChannelRoles}.
     * </p>
     *
     * <p>
     * <b>This parameter only computes roles; the crawl script must still apply them.</b> The
     * roles computed here reach the indexed document only by way of {@link #MESSAGE_ROLES}
     * ({@code message.roles}) in {@code scriptMap} -- typically {@code role=message.roles} (see
     * this module's README). Nothing in this class applies them on its own. A script that omits
     * that mapping silently discards every role this parameter computes: the document is indexed
     * under whatever {@code role} value the script otherwise supplies (commonly none), exactly as
     * unrestricted as if this parameter were {@code false} -- except that turning it {@code true}
     * still pays for {@code conversations.members} calls, still skips a private channel that
     * fails role resolution (a real cost with no offsetting benefit), and still silences the
     * {@link #isPermissionSync}-off-plus-{@code include_private} warning that would otherwise
     * flag this exact situation, since that warning cannot see whether a script actually consumes
     * the roles it computed. See {@code storeData}'s startup warning for the best-effort check
     * this class runs for that omission.
     * </p>
     */
    protected static final String PERMISSION_SYNC = "permission_sync";
    /**
     * Parameter name for a comma-separated list of additional permissions -- in the same
     * {@code {user}}/{@code {group}}/{@code {role}}-prefixed syntax the admin UI's own permission
     * fields use -- added to every document's roles, whatever channel it came from.
     *
     * <p>
     * Read only when {@link #PERMISSION_SYNC} is enabled: {@link #mergeAdditionalRoles} is called
     * from {@link #computeChannelRoles} and nowhere else, so with {@code permission_sync} off
     * this parameter is ignored entirely. Like every role computed there, it reaches the indexed
     * document only through an explicit {@code role=message.roles} script mapping, and not at all
     * for a channel that failed closed -- nothing from such a channel is indexed.
     * </p>
     *
     * <p>
     * Unlike the per-channel member roles this phase computes, and the DataConfig-level
     * permissions {@link #computeChannelRoles} merges in from {@code defaultDataMap}, a value
     * here is encoded via {@link PermissionHelper#encode} at merge time, matching {@code
     * OneDriveDataStore}'s {@code default_permissions} parameter (fess-ds-microsoft365) -- the
     * admin UI encodes its own permission fields on save, but a value typed into this
     * data-store-specific parameter has never passed through that encoding step.
     * </p>
     */
    protected static final String DEFAULT_PERMISSIONS = "default_permissions";

    /**
     * Message {@code subtype} values that are Slack-generated channel-administration
     * notifications rather than content a person wrote, dropped by default (see
     * {@link #isIgnoreSystemEvents}).
     *
     * <p>
     * Deliberately narrower than every subtype Slack documents at reference/events/message:
     * </p>
     * <ul>
     * <li>{@code file_share} and {@code reply_broadcast} are excluded from this set on purpose --
     * Slack documents both as "no longer served", so a message can never carry them.</li>
     * <li>{@code thread_broadcast} is excluded from this set on purpose -- it is a real message
     * a person wrote, broadcast to the channel in addition to its thread, not a system
     * notification. {@code SlackClient#getMessageReplies} already skips it for an unrelated
     * reason (avoiding double-indexing a reply reachable both from conversations.history and
     * conversations.replies); it must still be indexed once, not zero times.</li>
     * <li>{@code bot_message} and {@code me_message} are excluded from this set on purpose --
     * both carry content a person (or an integration acting on a channel's behalf) chose to
     * post, not a Slack-generated notification.</li>
     * <li>{@code message_changed}/{@code message_deleted}/{@code message_replied} are excluded
     * from this set because they are moot: Slack documents all three as {@code hidden: true} and
     * says they "will not return in calls to conversations.history", so this class never sees
     * them regardless.</li>
     * </ul>
     */
    protected static final Set<String> SYSTEM_EVENT_SUBTYPES = Set.of("channel_join", "channel_leave", "channel_topic", "channel_purpose",
            "channel_name", "channel_archive", "channel_unarchive", "group_join", "group_leave", "group_topic", "group_purpose",
            "group_name", "group_archive", "group_unarchive", "pinned_item", "unpinned_item");

    /**
     * Parameter keys withheld from crawl scripts: credentials and proxy configuration.
     *
     * <p>
     * This is a denylist over an otherwise-complete copy of the parameter map, not an
     * allowlist: any new parameter is exposed to scripts by default. Whoever adds a new
     * credential-shaped parameter (a token, a password, a secret key, ...) must add its key
     * here, or it leaks into every script's data map the same way {@link SlackClient#TOKEN_PARAM}
     * did before this set existed.
     * </p>
     */
    protected static final Set<String> SECRET_PARAMS =
            Set.of(SlackClient.TOKEN_PARAM, SlackClient.PROXY_HOST_PARAM, SlackClient.PROXY_PORT_PARAM);

    // scripts
    /** Script field name for message data. */
    protected static final String MESSAGE = "message";
    /** Script field name for message title. */
    protected static final String MESSAGE_TITLE = "title";
    /** Script field name for message text content. */
    protected static final String MESSAGE_TEXT = "text";
    /** Script field name for team information. */
    protected static final String MESSAGE_TEAM = "team";
    /** Script field name for message timestamp. */
    protected static final String MESSAGE_TIMESTAMP = "timestamp";
    /** Script field name for user information. */
    protected static final String MESSAGE_USER = "user";
    /** Script field name for channel information. */
    protected static final String MESSAGE_CHANNEL = "channel";
    /** Script field name for message permalink. */
    protected static final String MESSAGE_PERMALINK = "permalink";
    /** Script field name for message attachments. */
    protected static final String MESSAGE_ATTACHMENTS = "attachments";
    /**
     * Script field name for the search roles allowed to see this message/file, exposed as
     * {@code message.roles} only when {@link #PERMISSION_SYNC} is enabled -- see
     * {@link #computeChannelRoles} for how it is computed and {@link #processMessage}/{@link
     * #processFile} for why it is omitted entirely, not exposed as an empty list, when the
     * feature is off (byte-identical backward compatibility, F9/D5).
     */
    protected static final String MESSAGE_ROLES = "roles";

    /** Name of the content extractor to use for file processing. */
    protected String extractorName = "tikaExtractor";

    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Sets the name of the content extractor to use for file processing.
     *
     * @param extractorName the extractor name
     */
    public void setExtractorName(final String extractorName) {
        this.extractorName = extractorName;
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final Map<String, Object> configMap = new HashMap<>();
        configMap.put(MAX_FILESIZE, getMaxFilesize(paramMap));
        configMap.put(MAX_CONTENT_LENGTH, getMaxContentLength(paramMap));
        configMap.put(IGNORE_ERROR, isIgnoreError(paramMap));
        configMap.put(SUPPORTED_MIMETYPES, getSupportedMimeTypes(paramMap));
        configMap.put(FILE_CRAWL, isFileCrawl(paramMap));
        configMap.put(IGNORE_SYSTEM_EVENTS, isIgnoreSystemEvents(paramMap));
        configMap.put(READ_INTERVAL, getReadInterval(paramMap));
        configMap.put(URL_FILTER, getUrlFilter(paramMap));
        configMap.put(PERMISSION_SYNC, isPermissionSync(paramMap));
        if (logger.isDebugEnabled()) {
            logger.debug("configMap: {}", configMap);
        }

        final ExecutorService executorService = newFixedThreadPool(Integer.parseInt(paramMap.getAsString(NUMBER_OF_THREADS, "1")));
        // A fatal SlackApiException thrown inside a worker thread (conversations.replies, run
        // from processChannelMessages's executorService.execute(...)) does not propagate to this
        // method the way one thrown on this thread does: ThreadPoolExecutor swallows an
        // uncaught RuntimeException from its Runnable via the default uncaught-exception handler
        // instead of re-throwing it to the submitter. Without this latch, storeData would return
        // normally -- reporting success -- after a worker silently ate a fatal error. Only the
        // first one is kept: a later failure on another worker is very likely the same fatal
        // condition recurring, not new information.
        final AtomicReference<SlackApiException> fatalError = new AtomicReference<>();
        // Scoped to this one crawl, deliberately NOT the inherited AbstractDataStore#alive flag.
        // A data store is a LastaDi singleton (see fess_ds++.xml) that DataStoreFactory hands to
        // every crawl for the lifetime of the JVM, and nothing in Fess ever sets `alive` back to
        // true -- it is assigned exactly once, at field initialisation. Calling stop() to abort
        // one crawl would therefore leave every later crawl of every Slack DataConfig walking
        // zero channels and indexing zero documents, silently and permanently. A local
        // AtomicBoolean, rather than a plain local variable, because the abort can be requested
        // from a worker thread (see resolveFailureUrl, reached from processMessage/processFile
        // via executorService) while this method's own channel walk reads it.
        final AtomicBoolean crawlAlive = new AtomicBoolean(true);
        configMap.put(CRAWL_ALIVE, crawlAlive);
        // Counts private channels skipped by the fail-closed rule in computeChannelRoles, so
        // storeData can report a single aggregate warning below instead of one warning per
        // channel -- which would bury the signal in a large workspace (design plan D3).
        final AtomicInteger skippedChannelCount = new AtomicInteger();
        // IMPORTANT-3 (whole-branch review, Phase 3): counts members across all private
        // channels -- skipped or not -- whose email did not resolve. A channel with 9 of 10
        // members resolving is still indexed (resolvedCount > 0), but the tenth person silently
        // gets no role for it; this fires on every private channel containing a bot user, since a
        // bot has no profile email. Logged at debug only per member (see computeChannelRoles) but
        // folded into the aggregate warning below so the total is visible without enabling debug
        // logging for an entire crawl.
        final AtomicInteger unresolvedMemberCount = new AtomicInteger();
        // Important-1 (whole-branch review, Phase 3): File#getPermalink() is channel-independent
        // -- files.list?channel=... returns the same file for every channel it is shared into --
        // unlike a message's permalink, which is always channel-scoped. Accumulates, per file
        // URL, the union of every channel's roles seen so far this crawl, so a file shared into
        // both a private and a public channel stays visible to (at least) the private channel's
        // members regardless of which channel conversations.list happens to walk last -- not
        // controllable by an operator. A union of allowed principals is less restrictive, not
        // more; see FileRoles for what that does and does not buy, and for why the merge and
        // the callback.store it feeds share one monitor per URL.
        final Map<String, FileRoles> fileRolesByUrl = new ConcurrentHashMap<>();
        // A supplier over both flags rather than a one-time snapshot, so a stop that lands after
        // this client is constructed is still seen by every paging loop SlackClient runs
        // afterward. The two are different stops and both must be honoured: `alive` is the
        // operator's admin-UI stop button (set by DataIndexHelper on the shared singleton, and
        // never reset), crawlAlive is this crawl's own abort.
        try (final SlackClient client = new SlackClient(paramMap, () -> alive && crawlAlive.get())) {
            final Team team = client.getTeam();
            final boolean fileCrawl = (Boolean) configMap.get(FILE_CRAWL);
            final boolean permissionSync = (Boolean) configMap.get(PERMISSION_SYNC);
            // D1: this combination is not forbidden -- an existing operator may already run with
            // it, and forbidding it would be a breaking change -- but it is worth calling out
            // once per crawl: every private channel's content is indexed under only the
            // DataConfig-level permissions configured for this crawl, not per-channel
            // membership, which is a de facto publish switch if that permission field happens to
            // be left empty. Minor (whole-branch review, Phase 3): gated on that field actually
            // restricting something -- an operator who *has* set a DataConfig permission is not
            // running unrestricted, so warning regardless was a false alarm on every one of their
            // crawls. Guest-aware, not merely an emptiness check: the admin UI pre-fills that
            // field with {role}guest, which every anonymous searcher holds, so an emptiness check
            // would stay silent in precisely the shipped default configuration this warns about
            // (see isDataConfigPermissionGuestOnly).
            if (!permissionSync && Boolean.TRUE.equals(client.includePrivate) && isDataConfigPermissionGuestOnly(defaultDataMap)) {
                logger.warn("permission_sync is disabled but include_private is enabled: private channel content will be indexed "
                        + "using only the DataConfig-level permissions configured for this crawl, not each channel's own "
                        + "membership. Set permission_sync=true to restrict each private channel's documents to that channel's "
                        + "members.");
            }
            // Critical (whole-branch review, Phase 3): permission_sync only computes roles and
            // exposes them as message.roles (see MESSAGE_ROLES) -- nothing applies them to the
            // indexed document unless scriptMap itself maps them, typically role=message.roles.
            // A crawl config that forgets that mapping pays every cost of this feature (extra
            // conversations.members calls, private channels skipped on a fail-closed membership
            // lookup) for zero benefit: every document is indexed exactly as unrestricted as
            // before. This is a best-effort textual check, not proof the script actually uses the
            // roles correctly -- a script could reference message.roles and still discard it, or
            // reference it only in an unrelated expression -- but a scriptMap with no mention of
            // it at all is unambiguously the silent-no-op case this converts into a loud one.
            if (permissionSync && scriptMap.values().stream().noneMatch(value -> value != null && value.contains("message.roles"))) {
                logger.warn("permission_sync is enabled, but no script value references message.roles: the per-channel/file roles "
                        + "this computes will be silently discarded and every document will be indexed without them, exactly as if "
                        + "permission_sync were disabled. Add role=message.roles to this crawl's scripts to apply them. See this "
                        + "module's README for details.");
            }
            // A public channel contributes no member roles of its own by design (D2), so with
            // neither of the two additional sources set, computeChannelRoles returns an empty
            // list for it. An empty list is not "unrestricted": QueryHelper#buildRoleQuery only
            // adds should-clauses inside a filter(), so a document carrying no role term matches
            // no search-time role query at all and is findable by nobody -- administrators
            // included. Checked once here rather than per channel: the inputs are crawl-wide, so
            // a per-channel check would say the same thing once per public channel.
            if (permissionSync && StringUtil.isBlank(paramMap.getAsString(DEFAULT_PERMISSIONS, StringUtil.EMPTY))
                    && isDataConfigPermissionEmpty(defaultDataMap)) {
                logger.warn("permission_sync is enabled, but default_permissions is not set and this crawl's DataConfig permission "
                        + "field is empty: public channels have no roles of their own, so with role=message.roles mapped their "
                        + "documents are indexed with an empty role list, which matches no search-time role query -- they are "
                        + "findable by nobody, not even an administrator. Set default_permissions (or this crawl's DataConfig "
                        + "permission field) to the audience that should be able to see public-channel content.");
            }
            // Minor (whole-branch review, Phase 3): wrapped in try/finally so the aggregate
            // warning below still reports whatever skippedChannelCount/unresolvedMemberCount
            // accumulated even if a SlackApiException escapes getChannels (e.g. a fatal or
            // transient conversations.list/conversations.members error) -- otherwise a crawl that
            // partially ran before failing lost that count entirely, along with the exception
            // itself still propagating unchanged out of storeData.
            try {
                client.getChannels(channel -> {
                    // Checked here, not only inside SlackClient's paging loops: this is the loop
                    // that decides whether to dispatch the *next* channel at all, so stopping here
                    // skips channels that have not been started yet instead of only cutting short
                    // the one already in progress.
                    if (!alive || !crawlAlive.get()) {
                        return;
                    }
                    // null means "permission_sync is off; do not expose message.roles at all",
                    // not "this channel has zero roles" -- see computeChannelRoles and
                    // MESSAGE_ROLES.
                    List<String> roles = null;
                    if (permissionSync) {
                        roles = computeChannelRoles(client, paramMap, defaultDataMap, dataConfig, channel, skippedChannelCount,
                                unresolvedMemberCount);
                        if (roles == null) {
                            // Fail-closed (design plan D3): membership could not be established
                            // for this private channel, so none of its documents are indexed at
                            // all this crawl, rather than indexed with the wrong (too permissive)
                            // roles.
                            return;
                        }
                    }
                    processChannelMessages(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client,
                            team, channel, fatalError, roles);
                    if (fileCrawl) {
                        processChannelFiles(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, executorService, client,
                                team, channel, fatalError, roles, fileRolesByUrl);
                    }
                });
            } finally {
                final int skipped = skippedChannelCount.get();
                final int unresolvedMembers = unresolvedMemberCount.get();
                if (skipped > 0 || unresolvedMembers > 0) {
                    // Minor: worded to cover all three fail-closed conditions computeChannelRoles
                    // can count into `skipped` (a members lookup failure, an anomalous empty
                    // membership, or members present but none resolved to a role), not only the
                    // last of the three, and to distinguish it from `unresolvedMembers` -- members
                    // in a channel that was *not* skipped (at least one other member resolved)
                    // but who individually lost access to it (IMPORTANT-3).
                    final StringBuilder message = new StringBuilder();
                    if (skipped > 0) {
                        message.append(skipped)
                                .append(" private channel(s) skipped because their membership could not be reliably established; "
                                        + "permission_sync fails closed rather than index them without a working access-control list.");
                    }
                    if (unresolvedMembers > 0) {
                        if (message.length() > 0) {
                            message.append(' ');
                        }
                        message.append(unresolvedMembers)
                                .append(" member(s) across all private channels did not resolve to a role (commonly a bot user, "
                                        + "which has no profile email) and so did not get access to that channel's content, even "
                                        + "though the channel itself was otherwise indexed.");
                    }
                    logger.warn(message.toString());
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Shutting down thread executor.");
            }

            final int executorTimeout = getExecutorTimeout(paramMap);
            executorService.shutdown();
            if (!executorService.awaitTermination(executorTimeout, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate within {} seconds ({}={}); interrupting the tasks still running.", executorTimeout,
                        EXECUTOR_TIMEOUT, executorTimeout);
                // The JDK's documented two-phase shutdown, and the reason it matters here:
                // shutdownNow() only *requests* cancellation and returns immediately, so a
                // single wait would let the fatalError.get() below run while a worker is still
                // unwinding -- and a worker that latches a fatal error during that unwind would
                // be lost, leaving a failed crawl to report success. One request can legitimately
                // outlive the first window: max_retry_count retries, each waiting up to a minute
                // or for as long as Slack's Retry-After asks. A second bounded wait narrows that
                // race rather than closing it, which is why the give-up is logged at warn.
                executorService.shutdownNow();
                if (!executorService.awaitTermination(executorTimeout, TimeUnit.SECONDS)) {
                    logger.warn("Executor did not terminate {} seconds after being interrupted either; queued crawl work was "
                            + "discarded, and a failure discovered from here on cannot be reported.", executorTimeout);
                }
            }
        } catch (final InterruptedException e) {
            // Restore the interrupt flag before propagating, matching Request.sleepBeforeRetry's
            // handling of the same situation: swallowing it here would leave later code on this
            // thread with no way to observe that an interrupt occurred at all.
            Thread.currentThread().interrupt();
            // A fatal error latched by a worker (see latchFatalError) is more actionable than an
            // interrupt racing it on the main thread -- prefer surfacing that over discarding it
            // in favor of InterruptedRuntimeException, matching the same preference storeData
            // applies below via fatalError.get() once the executor shuts down cleanly.
            final SlackApiException fatalOnInterrupt = fatalError.get();
            if (fatalOnInterrupt != null) {
                throw fatalOnInterrupt;
            }
            throw new InterruptedRuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }

        // Checked after the executor has been shut down and awaited above, so this reports the
        // final state of the crawl rather than a state that could still change. Skipped when a
        // fatal error was also latched: that is thrown below and is the more actionable of the
        // two for an operator to see.
        if (!alive && fatalError.get() == null) {
            logger.info("Slack crawl for \"{}\" was stopped before completing; the index may contain fewer documents than a full crawl.",
                    dataConfig.getName());
        }

        // Re-thrown after the executor has been shut down and awaited above. On the normal path
        // awaitTermination returned true, so every worker has finished and none can still be
        // touching paramMap/callback. If both waits timed out instead, that guarantee does not
        // hold -- awaitTermination returning false means by definition the pool has not
        // terminated -- which is what the second warning above reports.
        final SlackApiException fatalException = fatalError.get();
        if (fatalException != null) {
            throw fatalException;
        }
    }

    /**
     * Parses a long configuration parameter, falling back to a default with a warning instead of
     * silently reverting when the value is not a number.
     *
     * <p>
     * Shared by {@link #getMaxFilesize} and {@link #getMaxContentLength}, matching the
     * "non-numeric falls back to the default, with a warning" contract {@code
     * SlackClient#getIntParam} already established for this module's other numeric parameters --
     * before this, these two silently reverted a typo'd value with no warning at all, while
     * {@link #getExecutorTimeout} and every parameter this phase added does warn.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @param paramName the parameter name to read
     * @param defaultValue the value to fall back to
     * @return the parsed value, or {@code defaultValue} if unset or not a number
     */
    protected long getLongParam(final DataStoreParams paramMap, final String paramName, final long defaultValue) {
        final String value = paramMap.getAsString(paramName);
        try {
            return StringUtil.isNotBlank(value) ? Long.parseLong(value) : defaultValue;
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Extracts the maximum file size configuration from parameters.
     *
     * <p>
     * A blank or non-numeric value falls back to {@link #DEFAULT_MAX_FILESIZE} with a warning
     * rather than failing the crawl or silently reverting.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the maximum file size in bytes
     */
    protected long getMaxFilesize(final DataStoreParams paramMap) {
        return getLongParam(paramMap, MAX_FILESIZE, DEFAULT_MAX_FILESIZE);
    }

    /**
     * Extracts the maximum content length configuration -- the post-download size bound applied
     * by {@code ExtractorBuilder} -- from parameters. See {@link #MAX_CONTENT_LENGTH} for how
     * this relates to {@link #getMaxFilesize}, the pre-download transfer bound.
     *
     * <p>
     * A blank or non-numeric value falls back to {@link #DEFAULT_MAX_CONTENT_LENGTH} ({@code
     * -1}), same as an unset parameter: both mean "defer to {@code ContentLengthHelper}". A
     * non-numeric value also now warns, matching {@link #getMaxFilesize}.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the maximum content length in bytes, or a negative value to defer to {@code
     *         ContentLengthHelper}'s per-MIME-type limit
     */
    protected long getMaxContentLength(final DataStoreParams paramMap) {
        return getLongParam(paramMap, MAX_CONTENT_LENGTH, DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Extracts, in seconds, how long {@link #storeData} should wait for queued crawl work to
     * finish before forcing shutdown.
     *
     * <p>
     * A non-numeric value falls back to {@link #DEFAULT_EXECUTOR_TIMEOUT} with a warning rather
     * than failing the crawl. A numeric but negative value is just as unusable -- {@code
     * awaitTermination(negative, SECONDS)} returns immediately without waiting at all, discarding
     * the entire backlog with nothing but a single warning -- so it also falls back to the
     * default, with its own warning, matching the clamp {@code connection_timeout}, {@code
     * read_timeout}, and {@code retry_interval} already get in {@code SlackClient}.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return the number of seconds to wait for queued work to finish before forcing shutdown
     */
    protected int getExecutorTimeout(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(EXECUTOR_TIMEOUT);
        final int parsed;
        try {
            parsed = StringUtil.isNotBlank(value) ? Integer.parseInt(value) : DEFAULT_EXECUTOR_TIMEOUT;
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", EXECUTOR_TIMEOUT, value, DEFAULT_EXECUTOR_TIMEOUT);
            return DEFAULT_EXECUTOR_TIMEOUT;
        }
        if (parsed < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", EXECUTOR_TIMEOUT, parsed, DEFAULT_EXECUTOR_TIMEOUT);
            return DEFAULT_EXECUTOR_TIMEOUT;
        }
        return parsed;
    }

    /**
     * Determines whether errors should be ignored during crawling.
     *
     * @param paramMap the configuration parameters
     * @return true if errors should be ignored, false otherwise
     */
    protected boolean isIgnoreError(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(IGNORE_ERROR, Constants.TRUE));
    }

    /**
     * Extracts the list of supported MIME type patterns from parameters.
     *
     * @param paramMap the configuration parameters
     * @return the list of supported MIME type patterns
     */
    protected List<String> getSupportedMimeTypes(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(SUPPORTED_MIMETYPES, MATCH_ALL_MIMETYPES);
        final String effective = StringUtil.isNotBlank(value) ? value : MATCH_ALL_MIMETYPES;
        return Arrays.stream(StringUtil.split(effective, ",")).map(String::trim).collect(Collectors.toList());
    }

    /**
     * Determines whether file crawling is enabled.
     *
     * @param paramMap the configuration parameters
     * @return true if file crawling is enabled, false otherwise
     */
    protected boolean isFileCrawl(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(FILE_CRAWL, Constants.FALSE));
    }

    /**
     * Determines whether ACL synchronisation is enabled. See {@link #PERMISSION_SYNC} for what
     * this does and why it defaults to {@code false}.
     *
     * @param paramMap the configuration parameters
     * @return true if per-channel member roles should be resolved and enforced
     */
    protected boolean isPermissionSync(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(PERMISSION_SYNC, Constants.FALSE));
    }

    /**
     * Determines whether Slack-generated channel-administration messages (see
     * {@link #SYSTEM_EVENT_SUBTYPES}) should be excluded from indexing.
     *
     * <p>
     * Defaults to {@code true}, unlike every other parameter this PR adds: {@code channel_join}/
     * {@code channel_leave} and the rest of {@link #SYSTEM_EVENT_SUBTYPES} are search noise, not
     * content an operator is likely to want indexed, and four of the connectors surveyed for this
     * plugin's design exclude them by default too. A crawl run without setting this parameter
     * explicitly indexes fewer documents than it did before this parameter existed.
     * </p>
     *
     * @param paramMap the configuration parameters
     * @return true if system-event messages should be excluded, false otherwise
     */
    protected boolean isIgnoreSystemEvents(final DataStoreParams paramMap) {
        return Constants.TRUE.equalsIgnoreCase(paramMap.getAsString(IGNORE_SYSTEM_EVENTS, Constants.TRUE));
    }

    /**
     * Returns whether the given message is a Slack-generated channel-administration
     * notification that {@link #isIgnoreSystemEvents} would exclude.
     *
     * @param message the message to test
     * @return true if the message's subtype is one of {@link #SYSTEM_EVENT_SUBTYPES}
     */
    protected boolean isSystemEventMessage(final Message message) {
        final String subtype = message.getSubtype();
        return subtype != null && SYSTEM_EVENT_SUBTYPES.contains(subtype);
    }

    /**
     * Creates and configures a URL filter based on include/exclude patterns.
     *
     * @param paramMap the configuration parameters
     * @return the configured URL filter, or null if the {@link UrlFilter} component is not
     *         registered
     */
    protected UrlFilter getUrlFilter(final DataStoreParams paramMap) {
        final UrlFilter urlFilter;
        try {
            urlFilter = ComponentUtil.getComponent(UrlFilter.class);
        } catch (final ComponentNotFoundException e) {
            logger.warn("UrlFilter component is not registered; {} and {} will not be applied.", INCLUDE_PATTERN, EXCLUDE_PATTERN, e);
            return null;
        }
        final String include = paramMap.getAsString(INCLUDE_PATTERN);
        if (StringUtil.isNotBlank(include)) {
            urlFilter.addInclude(include);
        }
        final String exclude = paramMap.getAsString(EXCLUDE_PATTERN);
        if (StringUtil.isNotBlank(exclude)) {
            urlFilter.addExclude(exclude);
        }
        urlFilter.init(paramMap.getAsString(Constants.CRAWLING_INFO_ID));
        if (logger.isDebugEnabled()) {
            logger.debug("urlFilter: {}", urlFilter);
        }
        return urlFilter;
    }

    /**
     * Creates a fixed thread pool executor for parallel processing.
     *
     * @param nThreads the number of threads in the pool
     * @return the configured executor service
     */
    protected ExecutorService newFixedThreadPool(final int nThreads) {
        if (logger.isDebugEnabled()) {
            logger.debug("Executor Thread Pool: {}", nThreads);
        }
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(nThreads),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Records a channel {@link #computeChannelRoles} skipped for a fail-closed reason via {@link
     * FailureUrlService}, so it shows up in the admin UI and survives log rotation instead of
     * being visible only through the aggregate warning {@code storeData} logs at the end of the
     * channel walk (IMPORTANT-2, whole-branch review Phase 3, design spec Section 6.3).
     *
     * <p>
     * A channel has no permalink of its own the way a message or file does, so this constructs a
     * synthetic identifier ({@code channelId/channelName}) rather than a real URL, matching the
     * precedent {@link #processMessage} already sets for a message with no {@code ts}. The
     * objection that blocked recording this from inside {@link SlackClient} does not apply here:
     * {@code storeData} (and so {@link #computeChannelRoles}, which it calls) already has {@code
     * dataConfig} in scope, and this class already uses {@link FailureUrlService} elsewhere
     * ({@link #processMessage}, {@link #processFile}).
     * </p>
     *
     * @param dataConfig the data configuration this crawl is running under
     * @param channel the channel being skipped
     * @param reason a human-readable description of why this channel is being skipped, also used
     *            in the per-channel warning logged by the caller
     */
    protected void recordSkippedChannel(final DataConfig dataConfig, final Channel channel, final String reason) {
        final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
        failureUrlService.store(dataConfig, SlackDataStoreException.class.getCanonicalName(), channel.getId() + "/" + channel.getName(),
                new SlackDataStoreException(reason));
    }

    /**
     * Computes the search roles allowed to see {@code channel}'s content, or signals that the
     * channel must be skipped entirely this crawl. Only called when {@link #PERMISSION_SYNC} is
     * enabled (see {@code storeData}).
     *
     * <p>
     * <b>Public channels never call {@code conversations.members}</b> (design plan D2): every
     * connector surveyed for this feature's design treats a public channel as visible to the
     * whole workspace, and skipping the call here saves one Tier 4 API call per public channel on
     * top of that. Only a private channel ({@link Channel#isPrivate()}) has its membership
     * resolved into roles.
     * </p>
     *
     * <p>
     * <b>Fail-closed (design plan D3):</b> for a private channel, this returns {@code null} --
     * meaning "index nothing from this channel this crawl", not "no roles" -- in any of three
     * cases, all counted in {@code skippedChannelCount} for {@code storeData}'s single aggregate
     * warning rather than one warning per channel:
     * </p>
     * <ul>
     * <li>{@link SlackClient#getChannelMembers} reports failure (its {@code false} return) --
     * the channel's membership could not be determined at all.</li>
     * <li>{@link SlackClient#getChannelMembers} reports success with zero members (Ruling,
     * whole-branch review Phase 3). Not treated as a legitimately memberless channel: the
     * crawling token's own bot user must itself be a member of a private channel to call
     * {@code conversations.members} -- or read anything from the channel at all -- with a
     * meaningful result, so an empty member list here means membership could not actually be
     * established, not that the channel has no one who can see it.</li>
     * <li>The channel has one or more members but zero of them resolved to a role. {@link
     * Profile#getEmail()} returns {@code null}, not an exception, when the token lacks the
     * {@code users:read.email} scope, so without this check a missing scope would silently
     * produce a channel visible to nobody instead of a loud, actionable warning.</li>
     * </ul>
     *
     * <p>
     * When neither fail-closed condition applies (including every public channel), the resolved
     * member roles -- empty for a public channel -- are merged with {@link #DEFAULT_PERMISSIONS}
     * and the DataConfig permission already carried on {@code defaultDataMap} by {@link
     * #mergeAdditionalRoles}, deduplicated, and returned.
     * </p>
     *
     * @param client the Slack client, for {@code conversations.members} and cached user lookups
     * @param paramMap the parameter map, for {@link #DEFAULT_PERMISSIONS}
     * @param defaultDataMap the default data map, for the DataConfig-level permission (F8/F9)
     * @param dataConfig the data configuration, for recording a skipped channel via {@link
     *            FailureUrlService} (IMPORTANT-2, whole-branch review Phase 3)
     * @param channel the channel to compute roles for
     * @param skippedChannelCount incremented when this channel is skipped for any fail-closed
     *            reason above
     * @param unresolvedMemberCount incremented once per member of this channel whose email did
     *            not resolve (IMPORTANT-3), regardless of whether the channel itself ends up
     *            skipped -- distinct from {@code skippedChannelCount}'s third condition, which
     *            fires only when <em>every</em> member fails to resolve
     * @return the merged, deduplicated list of roles to expose as {@code message.roles}, or
     *         {@code null} if this channel must not be indexed this crawl
     */
    protected List<String> computeChannelRoles(final SlackClient client, final DataStoreParams paramMap,
            final Map<String, Object> defaultDataMap, final DataConfig dataConfig, final Channel channel,
            final AtomicInteger skippedChannelCount, final AtomicInteger unresolvedMemberCount) {
        final List<String> roles = new ArrayList<>();
        if (channel.isPrivate()) {
            final List<String> memberIds = new ArrayList<>();
            final boolean succeeded = client.getChannelMembers(channel.getId(), memberIds::add);
            if (!succeeded) {
                skippedChannelCount.incrementAndGet();
                final String reason = "Failed to get the members of private channel \"" + channel.getName() + "\" (" + channel.getId()
                        + "); permission_sync fails closed on a members lookup failure.";
                logger.warn("{} This channel will not be indexed this crawl.", reason);
                recordSkippedChannel(dataConfig, channel, reason);
                return null;
            }
            if (memberIds.isEmpty()) {
                skippedChannelCount.incrementAndGet();
                final String reason = "Private channel \"" + channel.getName() + "\" (" + channel.getId() + ") reported zero members, "
                        + "which is anomalous -- the crawling token's own bot user must itself be a member to read a private "
                        + "channel at all; permission_sync fails closed on an empty membership.";
                logger.warn("{} This channel will not be indexed this crawl.", reason);
                recordSkippedChannel(dataConfig, channel, reason);
                return null;
            }
            int resolvedCount = 0;
            for (final String memberId : memberIds) {
                final String email = getMemberEmail(client, memberId);
                // Minor: a blank check, not merely a null check -- getSearchRoleByUser("") would
                // otherwise produce the bogus role consisting of just the role-search-user
                // prefix, and resolvedCount++ below would count this member as resolved,
                // defeating the fail-closed condition that requires at least one member to
                // genuinely resolve.
                if (StringUtil.isBlank(email)) {
                    unresolvedMemberCount.incrementAndGet();
                    if (logger.isDebugEnabled()) {
                        logger.debug("No email for member {} of channel {} ({}); this member gets no role for it.", memberId,
                                channel.getName(), channel.getId());
                    }
                    continue;
                }
                roles.add(ComponentUtil.getSystemHelper().getSearchRoleByUser(email));
                resolvedCount++;
            }
            if (resolvedCount == 0) {
                // memberIds is guaranteed non-empty here: the isEmpty() case above already
                // returned.
                skippedChannelCount.incrementAndGet();
                final String reason = "Private channel \"" + channel.getName() + "\" (" + channel.getId() + ") has " + memberIds.size()
                        + " member(s) but none resolved to an email; permission_sync fails closed. This usually means the token is "
                        + "missing the users:read.email scope.";
                logger.warn("{} This channel will not be indexed this crawl.", reason);
                recordSkippedChannel(dataConfig, channel, reason);
                return null;
            }
        }
        mergeAdditionalRoles(paramMap, defaultDataMap, roles);
        return roles.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Looks up a channel member's email by user ID, using {@link SlackClient}'s already-preloaded
     * user cache (F4: no additional API call in the common case).
     *
     * @param client the Slack client
     * @param memberId the member's user ID
     * @return the member's email, or {@code null} if the user could not be looked up or carries
     *         no email (e.g. the token lacks the {@code users:read.email} scope -- see {@link
     *         Profile#getEmail()})
     * @throws SlackApiException if {@code users.info} failed with a fatal or transient Slack
     *             error. Returning {@code null} fails the channel closed, which is the right
     *             answer for a member whose email is genuinely unavailable and the wrong one for
     *             a revoked token: the operator would see channels being skipped for a reason
     *             recorded only at debug. The catch below therefore names only the three
     *             cache-miss types, as {@link #getUsername} does.
     */
    protected String getMemberEmail(final SlackClient client, final String memberId) {
        try {
            final User user = client.getUser(memberId);
            if (user == null) {
                return null;
            }
            final Profile profile = user.getProfile();
            return profile == null ? null : profile.getEmail();
        } catch (final ExecutionException | UncheckedExecutionException | InvalidCacheLoadException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to look up member {} for role resolution.", memberId, e);
            }
            return null;
        }
    }

    /**
     * Merges {@link #DEFAULT_PERMISSIONS} and the DataConfig-level permission already carried on
     * {@code defaultDataMap} into {@code roles}, in place.
     *
     * <p>
     * <b>Two different encoding rules, on purpose (design plan D5, F7/F8):</b> {@code
     * default_permissions} is a raw, {@code {user}}/{@code {group}}/{@code {role}}-prefixed value
     * an operator typed into this data-store-specific parameter, so it is run through {@link
     * PermissionHelper#encode} here, exactly as {@code OneDriveDataStore}'s (fess-ds-microsoft365)
     * same-named parameter does. The DataConfig permission, by contrast, was already encoded once
     * when an administrator saved it in the admin UI (F8) -- {@link
     * org.codelibs.fess.ds.AbstractDataStore#store} copies it onto {@code defaultDataMap} under
     * {@code fessConfig.getIndexFieldRole()} before {@code storeData} ever runs -- so it is merged
     * in as-is, not re-encoded. {@code TeamsDataStore} (fess-ds-microsoft365) omits this second
     * source entirely; that omission must not be copied (F15).
     * </p>
     *
     * <p>
     * <b>Corrected rationale (IMPORTANT-4, whole-branch review Phase 3):</b> an earlier revision
     * of this javadoc, and commit 43f2b3c's message, claimed re-encoding the DataConfig
     * permission here "would corrupt it". That is wrong: {@link PermissionHelper#encode} returns
     * any value not already starting with {@code (allow)}/{@code (deny)}/{@code {user}}/{@code
     * {group}}/{@code {role}} unchanged, and an admin-UI-saved permission is already in that
     * encoded form, so {@code encode} is idempotent on it -- re-encoding would be a harmless
     * no-op, not corruption. The actual risk this merge guards against is <b>omission</b>:
     * dropping this source (as {@code TeamsDataStore} does, F15) would silently delete the
     * operator's admin-UI-configured permissions from every indexed document's roles, not merely
     * fail to double-encode something that was never at risk of corruption.
     * </p>
     *
     * @param paramMap the parameter map, for {@link #DEFAULT_PERMISSIONS}
     * @param defaultDataMap the default data map, for the already-encoded DataConfig permission
     * @param roles the role list to merge into, in place
     */
    protected void mergeAdditionalRoles(final DataStoreParams paramMap, final Map<String, Object> defaultDataMap,
            final List<String> roles) {
        final PermissionHelper permissionHelper = ComponentUtil.getPermissionHelper();
        StreamUtil.split(paramMap.getAsString(DEFAULT_PERMISSIONS, StringUtil.EMPTY), ",")
                .of(stream -> stream.filter(StringUtil::isNotBlank).map(permissionHelper::encode).forEach(roles::add));
        if (defaultDataMap.get(ComponentUtil.getFessConfig().getIndexFieldRole()) instanceof final List<?> roleTypeList) {
            roleTypeList.stream().map(s -> (String) s).forEach(roles::add);
        }
    }

    /**
     * Returns whether {@code defaultDataMap} carries no DataConfig-level permission at all, i.e.
     * whether an operator has left this crawl's DataConfig permission field unset.
     *
     * <p>
     * A literal emptiness test, deliberately: it answers "would a document computed from this
     * field alone carry no roles at all", which is what {@code storeData}'s
     * documents-findable-by-nobody warning turns on. It is not what the D1 warning turns on -- a
     * field holding only guest permissions is populated, so this returns {@code false} for it,
     * yet it restricts nothing -- so that warning is gated on {@link
     * #isDataConfigPermissionGuestOnly} instead.
     * </p>
     *
     * @param defaultDataMap the default data map, for the DataConfig-level permission
     * @return {@code true} if {@code defaultDataMap} carries no non-empty DataConfig permission
     *         list under {@code fessConfig.getIndexFieldRole()}
     */
    protected boolean isDataConfigPermissionEmpty(final Map<String, Object> defaultDataMap) {
        return !(defaultDataMap.get(ComponentUtil.getFessConfig().getIndexFieldRole()) instanceof final List<?> roleTypeList)
                || roleTypeList.isEmpty();
    }

    /**
     * Returns whether this crawl's DataConfig permission field restricts nothing: either it is
     * empty ({@link #isDataConfigPermissionEmpty}), or every permission in it is one every
     * anonymous searcher already holds.
     *
     * <p>
     * A literally-empty check is not enough to gate the D1 warning, because the shipped default
     * is not an empty field. The admin UI pre-fills a new Data Store config's Permissions field
     * from {@code role.search.default.display.permissions}, whose default value is {@code
     * {role}guest}, and saves it encoded; {@code role.search.guest.permissions} then hands that
     * same permission to every anonymous searcher. So an operator who never touched the field
     * ships private-channel content readable by anyone -- the exact situation the warning exists
     * to catch -- while an emptiness check reports the field as populated and stays silent.
     * </p>
     *
     * <p>
     * The guest set is read from {@link
     * org.codelibs.fess.mylasta.direction.FessProp#getSearchGuestRoleList} rather than compared
     * against a hard-coded {@code Rguest}: it is exactly what {@code RoleQueryHelper} gives an
     * anonymous searcher, prefixes and all, so this stays correct in a deployment that has
     * reconfigured either property. Only consulted when the field is non-empty, so a crawl with
     * no DataConfig permission at all does not require {@code PermissionHelper} to be resolvable.
     * </p>
     *
     * @param defaultDataMap the default data map, for the DataConfig-level permission
     * @return {@code true} if the DataConfig permission list is empty, or grants nothing an
     *         anonymous searcher does not already have
     */
    protected boolean isDataConfigPermissionGuestOnly(final Map<String, Object> defaultDataMap) {
        if (isDataConfigPermissionEmpty(defaultDataMap)) {
            return true;
        }
        final List<?> roleTypeList = (List<?>) defaultDataMap.get(ComponentUtil.getFessConfig().getIndexFieldRole());
        final List<String> guestRoles = ComponentUtil.getFessConfig().getSearchGuestRoleList();
        return roleTypeList.stream().allMatch(guestRoles::contains);
    }

    /**
     * The roles accumulated so far this crawl for one file permalink, and the monitor that orders
     * the {@code callback.store} calls consuming them.
     *
     * <p>
     * <b>Why a file, not a message, needs this (Important-1, whole-branch review Phase 3):</b>
     * {@link File#getPermalink()} is channel-independent -- {@code files.list?channel=...}
     * returns the same file object, permalink included, for every channel it is shared into --
     * while {@link SlackDataStore#getMessagePermalink} always returns a channel-scoped URL.
     * Without this, a file shared into both a private and a public channel would be indexed under
     * whichever channel {@link SlackDataStore#processChannelFiles} happened to walk last --
     * {@code conversations.list} order, not controllable by an operator.
     * </p>
     *
     * <p>
     * <b>What the union actually is (correcting an earlier revision of this javadoc):</b> these
     * are <em>allowed</em> principals, so unioning two channels' role sets is strictly
     * <em>less</em> restrictive than either set alone, not more -- a file shared into a private
     * and a public channel ends up visible to the private channel's members <em>and</em> to
     * whoever holds the public channel's roles. That is the intended behaviour, and it matches
     * Slack itself, where such a file is visible from every channel it is shared into; what it is
     * not is a way to hold a file to the private channel's restriction once it has also been
     * shared publicly. What the union buys over overwriting is determinism: the stored role set no
     * longer depends on which channel was walked last, so it can neither drop the private
     * channel's members (public channel last, its roles overwriting theirs) nor retain them by
     * luck (private channel last).
     * </p>
     *
     * <p>
     * <b>Why a monitor, and not just an atomic merge (issue #41):</b> making the read-union-write
     * sequence atomic keeps every channel's contribution, but on its own it does not order the
     * {@code callback.store} calls that follow -- and store order is what decides the indexed
     * document: {@code IndexUpdateCallbackImpl#store} appends to its {@code DocList} in call
     * order, and a bulk request applies two operations on the same document id in that same
     * order, so the last store for a URL wins. There is no thread count at which that ordering
     * comes for free: {@link SlackDataStore#newFixedThreadPool} builds a {@link
     * ThreadPoolExecutor} whose queue holds only {@code nThreads} tasks, with {@link
     * ThreadPoolExecutor.CallerRunsPolicy}, so even the default {@code number_of_threads=1} runs
     * tasks on two threads -- once the single worker is busy and the one queue slot is full,
     * {@code execute} rejects and the submitting thread runs the task itself. Measured against
     * that exact construction: tasks ran on {@code [pool-1-thread-1, main]}, two of them at once.
     * A single-threaded crawl is not a configuration this class has.
     * </p>
     *
     * <p>
     * Nor is the window between a merge and the store it feeds narrow: in between, the thread runs
     * the whole {@code scriptMap} evaluation loop and then {@code IndexUpdateCallbackImpl#store},
     * whose first statement is {@code SystemHelper#calibrateCpuLoad()} -- which, under the default
     * {@code adaptive.load.control=50}, sleeps until system CPU load falls below 50%. Left
     * unordered, the store carrying the smaller union can land last, and the file is then indexed
     * with the roles of only the channels merged up to that point. When the task that stored last
     * is a public channel's, contributing no roles of its own, that is no roles at all: a file
     * shared into a private channel, indexed with no access-control list and so reachable by
     * everyone.
     * </p>
     *
     * <p>
     * {@link SlackDataStore#processFile} therefore holds this object's monitor from the merge
     * through {@code callback.store}. The merges for one permalink are then totally ordered, and
     * each store is issued inside the same critical section as its own merge; since the
     * accumulated set only ever grows, the last store for a permalink is therefore the one
     * carrying the fullest union. Only tasks for that same permalink ever wait on it, and a
     * file's download and content extraction are finished before it is taken, so neither is
     * serialized.
     * </p>
     */
    protected static class FileRoles {

        /** Every channel's roles recorded for this file so far, deduplicated; guarded by {@code this}. */
        private final List<String> roles = new ArrayList<>();

        /**
         * Creates an accumulator holding no roles yet.
         */
        protected FileRoles() {
            // nothing to initialise beyond the empty accumulator above
        }

        /**
         * Unions {@code channelRoles} into the roles already accumulated for this file by an
         * earlier channel this crawl.
         *
         * @param channelRoles the roles {@link SlackDataStore#computeChannelRoles} resolved for
         *            the channel currently being processed
         * @return a private copy of the deduplicated union of {@code channelRoles} and every
         *         other channel's roles already recorded for this file this crawl -- a copy, so
         *         the list handed to the document being indexed is not one a later channel's
         *         merge grows underneath it
         */
        protected synchronized List<String> merge(final List<String> channelRoles) {
            for (final String role : channelRoles) {
                if (!roles.contains(role)) {
                    roles.add(role);
                }
            }
            return new ArrayList<>(roles);
        }
    }

    /**
     * Processes all messages in a channel, including threaded replies.
     *
     * <p>
     * A message {@link #isSystemEventMessage} recognizes is dropped here, before it is even
     * dispatched to {@code executorService}, when {@link #isIgnoreSystemEvents} is on. Dropping
     * it here also skips the {@link #isThreadParent} check below, so any replies it has are
     * never fetched either -- see the assumption recorded at the drop site about why that is
     * believed safe for all of {@link #SYSTEM_EVENT_SUBTYPES}, not just the two the design spec
     * documents. Skipping a system-event message here also avoids a wasted {@code
     * chat.getPermalink} call that {@link #processMessage} would otherwise make to resolve its
     * URL. A dropped message does not count toward {@code read_interval} pacing below, since
     * nothing was dispatched for it.
     * </p>
     *
     * <p>
     * Sleeps for {@code read_interval} milliseconds (see {@link #getReadInterval}) after each
     * message actually dispatched, to pace the crawl against a rate-limited workspace.
     * </p>
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param executorService the executor service for parallel processing
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel to process
     * @param fatalError latch shared with {@link #storeData} for a fatal {@link SlackApiException}
     *            raised on this method's worker thread (see {@link #latchFatalError})
     * @param roles the search roles allowed to see this channel's content, exposed to scripts as
     *            {@code message.roles} -- {@code null} when {@link #PERMISSION_SYNC} is disabled,
     *            meaning the field is omitted entirely rather than exposed as an empty list (see
     *            {@link #MESSAGE_ROLES})
     */
    protected void processChannelMessages(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final ExecutorService executorService, final SlackClient client, final Team team,
            final Channel channel, final AtomicReference<SlackApiException> fatalError, final List<String> roles) {
        final boolean ignoreSystemEvents = (Boolean) configMap.get(IGNORE_SYSTEM_EVENTS);
        final long readInterval = (Long) configMap.get(READ_INTERVAL);
        client.getChannelMessages(channel.getId(), message -> {
            if (ignoreSystemEvents && isSystemEventMessage(message)) {
                // Dropping here also means isThreadParent(message)/processMessageReplies never
                // run for this message, so any replies it has would be silently lost if it had
                // any. Design spec F11 documents this as safe for channel_join/channel_leave
                // specifically -- conversations.replies on their ts returns thread_not_found,
                // i.e. Slack itself refuses to thread them -- but does not say so for the other
                // fourteen subtypes in SYSTEM_EVENT_SUBTYPES (channel_topic, pinned_item, ...).
                // Extending that guarantee to all of them is an assumption, not a verified fact:
                // they are Slack-generated channel-administration notices, so a reply thread on
                // one is not a scenario Slack's own UI offers, but this has not been confirmed
                // against a live workspace.
                if (logger.isDebugEnabled()) {
                    logger.debug("Skipping system event message (subtype={}) in channel {}", message.getSubtype(), channel.getId());
                }
                return;
            }
            safeExecute(executorService, () -> {
                try {
                    processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message,
                            roles);
                    if (isThreadParent(message)) {
                        processMessageReplies(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel,
                                message, roles);
                    }
                } catch (final SlackApiException e) {
                    latchFatalError(executorService, fatalError, (AtomicBoolean) configMap.get(CRAWL_ALIVE), e);
                } catch (final Throwable t) {
                    recordMessageTaskFailure(dataConfig, channel, message, t);
                }
            });
            if (readInterval > 0) {
                sleep(readInterval);
            }
        });
    }

    /**
     * Logs and records a failure that reached the end of one message's worker task, so it is
     * reported rather than discarded by the executor.
     *
     * <p>
     * Nothing else catches it. {@link ThreadPoolExecutor#execute} hands a throwable that escapes
     * its {@link Runnable} to the thread's default uncaught exception handler, which does not go
     * through this class's logger, does not fail the crawl, and does not record the document
     * anywhere -- the affected messages are simply absent from the index while the job reports
     * success.
     * </p>
     *
     * <p>
     * The gap this closes is {@link #processMessageReplies}, the one call in that task without a
     * catch of its own ({@link #processMessage} ends in {@code catch (Throwable)}). Its {@code
     * conversations.replies} walk runs on this worker thread and raises more than {@link
     * SlackApiException}: {@link SlackDataStoreException} out of {@code Request#parseResponse}
     * when the body cannot be parsed -- an intermediary answering with an HTML error page, the
     * case where silent partial indexing is hardest to notice -- a {@link NullPointerException}
     * when a reply page claims {@code has_more} but carries no {@code response_metadata} cursor, or
     * any runtime failure from the HTTP layer.
     * </p>
     *
     * <p>
     * Deliberately not routed to {@link #latchFatalError}: unlike the {@link SlackApiException}
     * caught beside it, a failure here says nothing about whether the rest of the crawl can
     * proceed, and every remaining channel and message would be discarded on the strength of one
     * bad response body. It is recorded as a per-document failure instead, which is what {@link
     * #processMessage} and {@link #processFile} already do with an unexpected throwable, and the
     * crawl continues.
     * </p>
     *
     * <p>
     * The identifier is the {@code channelId/ts} pair {@link #processMessage} falls back to when a
     * message has no permalink, not a real URL: a thread has no permalink of its own, and
     * resolving the parent's through {@link #getMessagePermalink} can issue a {@code
     * chat.getPermalink} call that fails the same way this handler is reporting on. It matches the
     * synthetic identifier {@link #recordSkippedChannel} uses for the same reason.
     * </p>
     *
     * @param dataConfig the data configuration the failure is recorded against
     * @param channel the channel the message belongs to
     * @param message the message whose task failed
     * @param t the failure that escaped the task
     */
    protected void recordMessageTaskFailure(final DataConfig dataConfig, final Channel channel, final Message message, final Throwable t) {
        final String url = channel.getId() + "/" + message.getTs();
        logger.warn("Failed to process a message or its thread replies at: {}", url, t);
        final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
        failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
    }

    /**
     * Returns whether the given message is the parent of a thread. Slack
     * identifies a parent by its thread_ts being equal to its ts; a reply
     * carries the parent's thread_ts and a different ts.
     *
     * @param message the message to test
     * @return true if the message starts a thread
     */
    protected boolean isThreadParent(final Message message) {
        final String threadTs = message.getThreadTs();
        return threadTs != null && threadTs.equals(message.getTs());
    }

    /**
     * Records the first fatal Slack API error seen by any worker thread, shuts the executor down
     * immediately, and stops this data store.
     *
     * <p>
     * Chosen over letting the backlog drain naturally: once the token itself is no longer valid,
     * every other queued or future {@code conversations.replies} call is going to fail the exact
     * same way, so continuing to run them only delays reporting a failure the crawl already knows
     * about. What keeps this safe for the caller -- {@code client.getChannels}/{@code
     * getChannelMessages}/{@code getChannelFiles} keep walking and dispatching on the calling
     * thread after this runs -- is this executor's {@link ThreadPoolExecutor.CallerRunsPolicy}
     * (see {@link #newFixedThreadPool}): once shut down, that policy silently drops a submitted
     * task instead of running or rejecting it, so nothing thrown here could ever mask the
     * {@link SlackApiException} latched. See {@link #safeExecute} for why its own catch of
     * {@link RejectedExecutionException} is not what provides that safety.
     * </p>
     *
     * <p>
     * <b>Clearing this crawl's stop flag closes the gap {@code shutdownNow()} alone left
     * open.</b> A
     * fatal error is discovered on a worker thread -- {@code conversations.replies}, dispatched
     * from {@link #processChannelMessages} -- but {@code client.getChannels}/{@code
     * getChannelMessages}/{@code getChannelFiles} keep walking and dispatching on the *main*
     * thread regardless, one page and one {@code read_interval} sleep at a time, until every
     * remaining channel and message has been silently discarded by the shut-down executor before
     * {@link #storeData} finally reports the failure. {@code invalid_auth} and the rest of
     * {@link SlackClient#FATAL_ERROR_CODES} self-correct within roughly a page, because the very
     * next main-thread call fails the same way and throws directly. {@code missing_scope} does
     * not self-correct: it is scoped per Slack Web API *method*, so a token missing only the
     * scope conversations.replies needs keeps succeeding on every main-thread
     * conversations.history call indefinitely while only the reply-fetching worker latches.
     * Clearing {@code crawlAlive} stops that walk: {@link #storeData}'s channel-dispatch loop
     * already checks it before dispatching the *next* channel, alongside the operator's admin-UI
     * stop, so the two compose. {@link #storeData}'s own post-walk check tolerates the crawl
     * having been stopped at the same time as a latched {@code fatalError} -- it skips the
     * "stopped early" info log and lets the more actionable exception take over -- because that
     * is precisely the state this method produces.
     * </p>
     *
     * <p>
     * Deliberately <em>not</em> {@link #stop()}. That flips the inherited
     * {@link org.codelibs.fess.ds.AbstractDataStore#alive} flag, which Fess assigns {@code true}
     * exactly once, at field initialisation, and never resets. A data store is a LastaDi
     * singleton reused by every crawl for the lifetime of the JVM, so latching a fatal error
     * that way would leave every subsequent crawl of every Slack {@code DataConfig} walking zero
     * channels, indexing nothing, and still reporting success -- until Fess is restarted. One
     * rate-limited {@code conversations.replies} that exhausts its retries is enough to trigger
     * it. The already-indexed documents survive -- {@code AbstractDataStore} stamps {@code
     * expires} on every one of them whenever {@code day.for.cleanup} is non-negative (it
     * defaults to 3), and {@code DataIndexHelper.deleteOldDocs} excludes documents that carry it
     * -- so the failure mode is a silently stale index rather than a deleted one, reported to
     * the operator as a successful crawl.
     * </p>
     *
     * @param executorService the executor to shut down
     * @param fatalError the latch shared with {@link #storeData}; only the first error is kept, since a
     *            second worker hitting this almost certainly means the same fatal condition, not
     *            new information
     * @param crawlAlive this crawl's stop flag, cleared so the main-thread channel walk stops
     *            dispatching
     * @param e the fatal error just caught
     */
    protected void latchFatalError(final ExecutorService executorService, final AtomicReference<SlackApiException> fatalError,
            final AtomicBoolean crawlAlive, final SlackApiException e) {
        if (fatalError.compareAndSet(null, e)) {
            executorService.shutdownNow();
            crawlAlive.set(false);
        }
    }

    /**
     * Submits {@code task} to {@code executorService}, catching {@link RejectedExecutionException}
     * as defence against a future change to this class's rejection policy, not behaviour this
     * executor actually exhibits today.
     *
     * <p>
     * {@link #newFixedThreadPool} builds this executor with {@link ThreadPoolExecutor.CallerRunsPolicy}.
     * Once the executor is shut down (see {@link #latchFatalError}), that policy's {@code
     * rejectedExecution} silently drops the task instead of running or throwing -- verified
     * empirically against this class's own executor, not merely assumed from the policy's name --
     * so in this class the catch below has no observable effect: a submission after shutdown is
     * discarded by the policy before {@link ExecutorService#execute} could ever throw. It is kept
     * as a guard for if that construction ever changes to a throwing policy (e.g. {@code
     * AbortPolicy}), in which case a rejected submission -- typically from {@link SlackClient}'s
     * paging loop, one channel/message/file at a time, which is not prepared to handle it -- would
     * otherwise mask the crawl's real, already-latched failure.
     * </p>
     *
     * @param executorService the executor to submit to
     * @param task the task to run
     */
    protected void safeExecute(final ExecutorService executorService, final Runnable task) {
        try {
            executorService.execute(task);
        } catch (final RejectedExecutionException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Discarding a task submitted after the executor was shut down for a fatal error.", e);
            }
        }
    }

    /**
     * Processes all files in a channel for indexing.
     *
     * <p>
     * Sleeps for {@code read_interval} milliseconds (see {@link #getReadInterval}) after each
     * file, to pace the crawl against a rate-limited workspace.
     * </p>
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param executorService the executor service for parallel processing
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel to process
     * @param fatalError latch shared with {@link #storeData} for a fatal {@link SlackApiException}
     *            raised on this method's worker thread (see {@link #latchFatalError})
     * @param roles the search roles allowed to see this channel's content; see
     *            {@link #processChannelMessages}'s matching parameter for what {@code null} means
     * @param fileRolesByUrl accumulates, per file permalink, the union of every channel's roles
     *            seen so far this crawl (see {@link FileRoles})
     */
    protected void processChannelFiles(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final ExecutorService executorService, final SlackClient client, final Team team, final Channel channel,
            final AtomicReference<SlackApiException> fatalError, final List<String> roles, final Map<String, FileRoles> fileRolesByUrl) {
        final long readInterval = (Long) configMap.get(READ_INTERVAL);
        client.getChannelFiles(channel.getId(), file -> {
            safeExecute(executorService, () -> {
                try {
                    processFile(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, file, roles,
                            fileRolesByUrl);
                } catch (final SlackApiException e) {
                    latchFatalError(executorService, fatalError, (AtomicBoolean) configMap.get(CRAWL_ALIVE), e);
                }
            });
            if (readInterval > 0) {
                sleep(readInterval);
            }
        });
    }

    /**
     * Processes all replies to a threaded message.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the thread
     * @param parentMessage the parent message of the thread
     * @param roles the search roles allowed to see this channel's content; see
     *            {@link #processChannelMessages}'s matching parameter for what {@code null} means
     */
    protected void processMessageReplies(final DataConfig dataConfig, final IndexUpdateCallback callback,
            final Map<String, Object> configMap, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final SlackClient client, final Team team, final Channel channel,
            final Message parentMessage, final List<String> roles) {
        client.getMessageReplies(channel.getId(), parentMessage.getThreadTs(), message -> {
            processMessage(dataConfig, callback, configMap, paramMap, scriptMap, defaultDataMap, client, team, channel, message, roles);
        });
    }

    /**
     * Builds the map exposed to crawl scripts, withholding credentials and
     * network configuration.
     *
     * <p>
     * {@link AbstractDataStore#convertValue} returns a value verbatim when a
     * script template matches a parameter key exactly, so a copy of the raw
     * parameter map would let a script such as {@code content=token} index
     * the OAuth token. The keys in {@link #SECRET_PARAMS} are withheld
     * instead of copied.
     * </p>
     *
     * @param paramMap the data store parameters
     * @return a new map safe to expose to scripts
     */
    protected Map<String, Object> newResultMap(final DataStoreParams paramMap) {
        final Map<String, Object> resultMap = new LinkedHashMap<>();
        paramMap.asMap().forEach((k, v) -> {
            if (!SECRET_PARAMS.contains(k)) {
                resultMap.put(k, v);
            }
        });
        return resultMap;
    }

    /**
     * Returns a per-document copy of {@code paramMap} carrying {@code statsKey} under
     * {@link Constants#CRAWLER_STATS_KEY}, for the single {@code callback.store} call that
     * document makes.
     *
     * <p>
     * The stats key identifies one document for statistics and logging; it is not crawl state
     * to be shared. This store dispatches {@link #processMessage} and {@link #processFile} to a
     * pool of {@code number_of_threads} workers that all receive the same {@code paramMap}
     * instance, so writing the key straight onto that instance lets one worker overwrite
     * another's between the write and the {@code callback.store} that consumes it. The
     * single-threaded stores write it directly and are correct doing so -- {@code CsvDataStore},
     * {@code DatabaseDataStore}, {@code JsonDataStore}, {@code GitDataStore} declare no executor
     * at all -- while the multi-threaded {@code ConfluenceDataStore} in fess-ds-atlassian takes
     * this same copy. This store is multi-threaded, so it follows the latter.
     * </p>
     *
     * <p>
     * {@link DataStoreParams#newInstance()} is a genuine shallow copy, not a view, so the copy's
     * key is invisible to the other workers. One side effect is worth stating: because the key
     * is now never written to the shared map, {@link #newResultMap} can no longer copy it into
     * the script scope. Groovy could not reach it by name -- {@code "crawler.stats.key"}
     * contains dots, so the name resolves as property navigation rather than as a binding --
     * but {@link AbstractDataStore#convertValue} returns a value verbatim when a script template
     * matches a resultMap key exactly, so a scriptMap entry of {@code field=crawler.stats.key}
     * did index the object, and under {@code number_of_threads > 1} the instance it indexed
     * could have belonged to a different document.
     * </p>
     *
     * @param paramMap the data store parameters shared by every worker thread
     * @param statsKey the stats key identifying the one document about to be stored
     * @return a copy of {@code paramMap} carrying {@code statsKey}
     */
    protected DataStoreParams newStatsParams(final DataStoreParams paramMap, final StatsKeyObject statsKey) {
        final DataStoreParams localParams = paramMap.newInstance();
        localParams.put(Constants.CRAWLER_STATS_KEY, statsKey);
        return localParams;
    }

    /**
     * Resolves the URL to record via {@code FailureUrlService} for a failed crawl, and, when the
     * failure asks to abort, clears this crawl's stop flag.
     *
     * <p>
     * Shared by both {@code catch (CrawlingAccessException)} blocks in {@link #processMessage}
     * and {@link #processFile}. Before this, both ignored {@link DataStoreCrawlingException#getUrl()}
     * and {@link DataStoreCrawlingException#aborted()} on a target that carried them, always
     * recording the caller-computed {@code fallbackUrl} (a message's permalink or a file's own
     * permalink) even when the exception named a more precise URL, and never reacting to a
     * request to abort. Matches the pattern in {@code fess-ds-example}'s
     * {@code ExampleDataStore}: that class uses a local {@code running} flag, which cannot be a
     * plain local variable here because {@link SlackDataStore} dispatches message and file
     * processing to worker threads (see {@code storeData}'s {@code executorService}), so one
     * caller's stack frame would not be seen by the others -- hence the shared
     * {@link AtomicBoolean}.
     * </p>
     *
     * <p>
     * Deliberately <em>not</em> {@link #stop()}. That flips the inherited
     * {@link org.codelibs.fess.ds.AbstractDataStore#alive} flag, which is assigned {@code true}
     * exactly once (at field initialisation) and is never reset by Fess. Because a data store is
     * a LastaDi singleton reused by every crawl for the lifetime of the JVM, aborting one crawl
     * that way would leave every subsequent crawl of every Slack {@code DataConfig} walking zero
     * channels and reporting success, until Fess is restarted.
     * </p>
     *
     * @param target the (possibly unwrapped) crawling exception being handled
     * @param fallbackUrl the URL to use when {@code target} carries none of its own
     * @param crawlAlive this crawl's stop flag, cleared when {@code target} asks to abort
     * @return the URL to record via {@code FailureUrlService}
     */
    protected String resolveFailureUrl(final Throwable target, final String fallbackUrl, final AtomicBoolean crawlAlive) {
        if (target instanceof final DataStoreCrawlingException dce) {
            if (dce.aborted()) {
                crawlAlive.set(false);
            }
            return dce.getUrl();
        }
        return fallbackUrl;
    }

    /**
     * Reports a per-document failure at warn without putting the document's field <em>values</em>
     * in the log: the URL and the field names identify the document, and the full map is
     * available at debug for anyone who turns it on deliberately.
     *
     * <p>
     * The four call sites (both {@code catch} blocks in {@link #processMessage} and {@link
     * #processFile}) logged the whole {@code dataMap} at warn, which predates {@link
     * #PERMISSION_SYNC}; what this feature changed is what that map contains. With {@code
     * permission_sync=true} and {@code role=message.roles}, {@code dataMap} carries the private
     * channel's member roles -- one per member, each an email address -- by the time either
     * {@code catch} block runs, so a single failing document dumped that channel's whole
     * membership roster into the log:
     * </p>
     *
     * <pre>
     * Crawling Access Exception at : {role=[1alice@example.com, 1bob@example.com], content=Hello}
     * </pre>
     *
     * <p>
     * That is a wider audience than the data itself has: a Fess administrator holding only {@code
     * admin-log}/{@code admin-logview} can download crawler logs without any access to this data
     * store's configuration, let alone to the private channels it crawls. Follows the same
     * warn-identifies/debug-details split {@code SlackClient#handleApiError} already uses for a
     * raw response body.
     * </p>
     *
     * @param url the document's URL, already resolved by the caller
     * @param dataMap the document being indexed when the failure occurred
     * @param t the failure to report
     */
    protected void logDocumentFailure(final String url, final Map<String, Object> dataMap, final Throwable t) {
        logger.warn("Crawling Access Exception at : {} (fields: {})", url, dataMap.keySet(), t);
        if (logger.isDebugEnabled()) {
            logger.debug("dataMap for {}: {}", url, dataMap);
        }
    }

    /**
     * Processes a single message for indexing, extracting content and metadata.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the message
     * @param message the message to process
     * @param roles the search roles allowed to see this message, exposed to scripts as {@code
     *            message.roles} -- omitted entirely, not exposed as an empty list, when {@code
     *            null} (see {@link #MESSAGE_ROLES})
     */
    protected void processMessage(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final Message message, final List<String> roles) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        // getMessagePermalink is resolved outside the main try/catch below because
        // CrawlerStatsHelper#begin requires StatsKeyObject to already carry a non-null
        // id; falling through to the main catch (Throwable) with a not-yet-constructed
        // statsKey would only trade this failure for an IllegalArgumentException out of
        // CrawlerStatsHelper itself. An unexpected failure here still does not abort the
        // channel's crawl: it is logged and a channel/timestamp-based identifier is used
        // in its place.
        String url;
        try {
            url = getMessagePermalink(client, team, channel, message);
        } catch (final SlackApiException e) {
            // Not a per-message failure, so it must not be traded for the fallback identifier
            // below: the worker task dispatching this call forwards it to latchFatalError.
            throw e;
        } catch (final Exception e) {
            logger.warn("Failed to get a permalink for a message in channel: {}", channel.getId(), e);
            url = null;
        }
        if (url == null) {
            // Only chat.getPermalink produces a null here, and only for a message-scoped error
            // (a fatal one threw above). SlackClient#getPermalink already logged the Slack error
            // code; substituting the same identifier the catch above uses keeps the message
            // indexed, where passing the null on gave new StatsKeyObject(null) and an
            // IllegalArgumentException out of CrawlerStatsHelper#begin instead -- recording the
            // message as a failure under an error that named neither Slack nor the cause.
            url = channel.getId() + "/" + message.getTs();
        }
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        final DataStoreParams localParams = newStatsParams(paramMap, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);

            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            logger.info("Crawling URL: {}", url);

            final Map<String, Object> resultMap = newResultMap(paramMap);
            final Map<String, Object> messageMap = new HashMap<>();

            final String messageText = getMessageText(message);
            final String username = getMessageUsername(client, message);
            messageMap.put(MESSAGE_TITLE, StringUtil.EMPTY);
            messageMap.put(MESSAGE_TEXT, messageText);
            // messageMap.put(MESSAGE_TEAM, team.getName());
            messageMap.put(MESSAGE_TIMESTAMP, getMessageTimestamp(message));
            messageMap.put(MESSAGE_USER, username);
            messageMap.put(MESSAGE_CHANNEL, channel.getName());
            messageMap.put(MESSAGE_PERMALINK, url);
            messageMap.put(MESSAGE_ATTACHMENTS, getMessageAttachmentsText(message));
            // Only put when non-null: null means permission_sync is disabled, and this key must
            // then be absent, not present with an empty list, so message.roles evaluates to null
            // in a script exactly as it did before this feature existed (F9/D5, backward
            // compatibility).
            if (roles != null) {
                messageMap.put(MESSAGE_ROLES, roles);
            }
            resultMap.put(MESSAGE, messageMap);

            crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

            if (logger.isDebugEnabled()) {
                logger.debug("messageMap: {}", messageMap);
            }

            final String scriptType = getScriptType(paramMap);
            for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                if (convertValue != null) {
                    dataMap.put(entry.getKey(), convertValue);
                }
            }

            crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

            if (logger.isDebugEnabled()) {
                logger.debug("dataMap: {}", dataMap);
            }

            if (dataMap.get("url") instanceof String statsUrl) {
                statsKey.setUrl(statsUrl);
            }

            callback.store(localParams, dataMap);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final CrawlingAccessException e) {
            logDocumentFailure(url, dataMap, e);

            Throwable target = e;
            if (target instanceof MultipleCrawlingAccessException ex) {
                final Throwable[] causes = ex.getCauses();
                if (causes.length > 0) {
                    target = causes[causes.length - 1];
                }
            }

            String errorName;
            final Throwable cause = target.getCause();
            if (cause != null) {
                errorName = cause.getClass().getCanonicalName();
            } else {
                errorName = target.getClass().getCanonicalName();
            }

            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, errorName, resolveFailureUrl(target, url, (AtomicBoolean) configMap.get(CRAWL_ALIVE)),
                    target);
            crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        } catch (final SlackApiException e) {
            // Must propagate uncaught, not fall into the generic catch below: the worker task
            // dispatching this call (see processChannelMessages/processChannelFiles) wraps it in
            // its own catch (SlackApiException) that forwards to latchFatalError, so storeData
            // fails the whole crawl instead of reporting a false success. Catching it here as a
            // plain Throwable would instead record it via FailureUrlService as an ordinary
            // per-item failure and let the crawl continue -- silently restoring the exact defect
            // this phase closed. This is now a live path, not a defensive one: the user, bot and
            // permalink lookups this method makes -- users.info, bots.info, chat.getPermalink --
            // all route through handleApiError, so a revoked token surfaces here rather than as a
            // per-message failure with an error that names neither Slack nor the cause.
            throw e;
        } catch (final Throwable t) {
            logDocumentFailure(url, dataMap, t);
            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    /**
     * Processes a single file for indexing, extracting content and metadata.
     *
     * @param dataConfig the data configuration
     * @param callback the index update callback
     * @param configMap the configuration map
     * @param paramMap the parameter map
     * @param scriptMap the script map
     * @param defaultDataMap the default data map
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the file
     * @param file the file to process
     * @param roles the search roles allowed to see this file; see {@link #processMessage}'s
     *            matching parameter for what {@code null} means
     * @param fileRolesByUrl accumulates, per file permalink, the union of every channel's roles
     *            seen so far this crawl (see {@link FileRoles})
     */
    protected void processFile(final DataConfig dataConfig, final IndexUpdateCallback callback, final Map<String, Object> configMap,
            final DataStoreParams paramMap, final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap,
            final SlackClient client, final Team team, final Channel channel, final File file, final List<String> roles,
            final Map<String, FileRoles> fileRolesByUrl) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final String url = file.getPermalink();
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        final DataStoreParams localParams = newStatsParams(paramMap, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);

            final String mimeType = file.getMimetype();
            final UrlFilter urlFilter = (UrlFilter) configMap.get(URL_FILTER);
            if (urlFilter != null && !urlFilter.match(url)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Not matched: {}", url);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            logger.info("Crawling URL: {}", url);

            final boolean ignoreError = (Boolean) configMap.get(IGNORE_ERROR);

            final Map<String, Object> resultMap = newResultMap(paramMap);
            final Map<String, Object> fileMap = new HashMap<>();

            final long maxFilesize = (Long) configMap.get(MAX_FILESIZE);
            if (file.getSize() > maxFilesize) {
                throw new MaxLengthExceededException(
                        "The content length (" + file.getSize() + " byte) is over " + maxFilesize + " byte. The url is " + url);
            }

            if (configMap.getOrDefault(SUPPORTED_MIMETYPES, EMPTY_LIST) instanceof List<?> supportedMimetypes
                    && supportedMimetypes.stream().map(o -> o.toString()).noneMatch(mimeType::matches)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} is not an indexing target.", mimeType);
                }
                crawlerStatsHelper.discard(statsKey);
                return;
            }

            final long maxContentLength = (Long) configMap.get(MAX_CONTENT_LENGTH);
            final String fileContent = getFileContent(client, file, maxContentLength, ignoreError);
            fileMap.put(MESSAGE_TITLE, getFileTitle(file));
            fileMap.put(MESSAGE_TEXT, getFileText(file, fileContent));
            // fileMap.put(MESSAGE_TEAM, team.getName());
            fileMap.put(MESSAGE_TIMESTAMP, getFileTimestamp(file));
            fileMap.put(MESSAGE_USER, getFileUsername(client, file));
            fileMap.put(MESSAGE_CHANNEL, channel.getName());
            fileMap.put(MESSAGE_PERMALINK, file.getPermalink());
            fileMap.put(MESSAGE_ATTACHMENTS, "");
            resultMap.put(MESSAGE, fileMap);

            // Everything from the script evaluation to the store, as one block, because it runs
            // inside the per-permalink critical section below when roles are being merged and
            // outside it when there are none to merge -- see FileRoles for why the merge and the
            // store it feeds must not be separable.
            final Runnable evaluateAndStore = () -> {
                crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

                if (logger.isDebugEnabled()) {
                    logger.debug("fileMap: {}", fileMap);
                }

                final String scriptType = getScriptType(paramMap);
                for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
                    final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
                    if (convertValue != null) {
                        dataMap.put(entry.getKey(), convertValue);
                    }
                }

                crawlerStatsHelper.record(statsKey, StatsAction.EVALUATED);

                if (logger.isDebugEnabled()) {
                    logger.debug("dataMap: {}", dataMap);
                }

                if (dataMap.get("url") instanceof String statsUrl) {
                    statsKey.setUrl(statsUrl);
                }
                callback.store(localParams, dataMap);
                crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
            };

            // See the matching guard in processMessage: null must mean "key absent", not "empty
            // list", to keep permission_sync=false byte-identical to before this feature (F9/D5)
            // -- down to not allocating an accumulator entry per file URL when the feature is off.
            if (roles == null) {
                evaluateAndStore.run();
            } else {
                // Merged (Important-1) rather than used as-is: file.getPermalink() is
                // channel-independent, so without this a file shared into multiple channels would
                // be indexed under whichever channel's roles happened to be stored last. Merge and
                // store are one critical section per permalink (issue #41): merging atomically
                // still left the store carrying the smaller union free to land last, indexing the
                // file with fewer roles than the channels it is shared into call for -- with none
                // at all, when the channel that stored last was a public one. Only another task
                // for this same permalink ever waits here, and only for a script evaluation and a
                // store: the download and extraction above are already done.
                final FileRoles fileRoles = fileRolesByUrl.computeIfAbsent(url, k -> new FileRoles());
                synchronized (fileRoles) {
                    fileMap.put(MESSAGE_ROLES, fileRoles.merge(roles));
                    evaluateAndStore.run();
                }
            }
        } catch (final CrawlingAccessException e) {
            logDocumentFailure(url, dataMap, e);

            Throwable target = e;
            if (target instanceof MultipleCrawlingAccessException ex) {
                final Throwable[] causes = ex.getCauses();
                if (causes.length > 0) {
                    target = causes[causes.length - 1];
                }
            }

            String errorName;
            final Throwable cause = target.getCause();
            if (cause != null) {
                errorName = cause.getClass().getCanonicalName();
            } else {
                errorName = target.getClass().getCanonicalName();
            }

            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, errorName, resolveFailureUrl(target, url, (AtomicBoolean) configMap.get(CRAWL_ALIVE)),
                    target);
            crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        } catch (final SlackApiException e) {
            // Must propagate uncaught, not fall into the generic catch below: the worker task
            // dispatching this call (see processChannelMessages/processChannelFiles) wraps it in
            // its own catch (SlackApiException) that forwards to latchFatalError, so storeData
            // fails the whole crawl instead of reporting a false success. Catching it here as a
            // plain Throwable would instead record it via FailureUrlService as an ordinary
            // per-item failure and let the crawl continue -- silently restoring the exact defect
            // this phase closed. This is now a live path, not a defensive one: getFileUsername's
            // users.info lookup routes through handleApiError, so a revoked token surfaces here
            // rather than as a per-file failure with an error that names neither Slack nor the
            // cause.
            throw e;
        } catch (final Throwable t) {
            logDocumentFailure(url, dataMap, t);
            final FailureUrlService failureUrlService = ComponentUtil.getComponent(FailureUrlService.class);
            failureUrlService.store(dataConfig, t.getClass().getCanonicalName(), url, t);
            crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    /**
     * Extracts the text content from a message.
     *
     * @param message the message to extract text from
     * @return the message text or empty string if null
     */
    protected String getMessageText(final Message message) {
        final String text = message.getText();
        return text != null ? text : "";
    }

    /**
     * Converts a message timestamp to a Date object.
     *
     * <p>
     * A message with no {@code ts} can still reach here with a non-empty permalink -- see
     * {@link #getMessagePermalink} -- so this must not assume {@code ts} is present. Mirrors
     * {@link #getFileTimestamp}, which returns {@code null} rather than throwing when a file
     * carries neither {@code created} nor {@code timestamp}: the message still indexes, just
     * without a timestamp, instead of failing with an unhelpful {@link NullPointerException}
     * recorded under an empty URL.
     * </p>
     *
     * @param message the message containing the timestamp
     * @return the timestamp as a Date object, or {@code null} if the message carries no {@code ts}
     */
    protected Date getMessageTimestamp(final Message message) {
        final String ts = message.getTs();
        if (ts == null) {
            return null;
        }
        return new Date(Math.round(Double.parseDouble(ts) * 1000));
    }

    /**
     * Converts a file's creation time to a Date object. Slack documents
     * {@code timestamp} as deprecated and kept only for backwards
     * compatibility, so {@code created} is preferred; {@code timestamp} is
     * used only when {@code created} is absent.
     *
     * @param file the file containing the creation time
     * @return the creation time as a Date object, or null if the file carries
     *         neither {@code created} nor {@code timestamp}
     */
    protected Date getFileTimestamp(final File file) {
        final Long created = file.getCreated();
        if (created != null) {
            return new Date(created.longValue() * 1000L);
        }
        final Long timestamp = file.getTimestamp();
        if (timestamp != null) {
            return new Date(timestamp.longValue() * 1000L);
        }
        return null;
    }

    /**
     * Extracts the username from a message, handling different message types.
     *
     * @param client the Slack client for user lookups
     * @param message the message to extract username from
     * @return the username or empty string if not found
     * @throws SlackApiException if a user or bot lookup failed with a fatal or transient Slack
     *             error; see {@link #getUsername} for why this is not absorbed here
     */
    public String getMessageUsername(final SlackClient client, final Message message) {
        try {
            if (message.getUser() != null) {
                return getUsername(client, message.getUser());
            }
            if (message.getSubtype() != null) {
                if ("bot_message".equals(message.getSubtype())) {
                    return client.getBot(message.getBotId()).getName();
                }
                if ("file_comment".equals(message.getSubtype())) {
                    return getUsername(client, message.getComment().getUser());
                }
            }
        } catch (final SlackApiException e) {
            // Must escape this method's catch-all, which is the widest of the three around a
            // lookup: it exists so an unknown user or a malformed message leaves the username
            // empty instead of failing the document, and a fatal Slack error is neither. The
            // worker task running this (processChannelMessages/processChannelFiles) forwards it
            // to latchFatalError, which is what aborts the crawl rather than letting it finish
            // with raw IDs in the author field. Note that the scheduler job still reports
            // success either way -- Fess catches a data store's exception per data config -- so
            // the abort is visible only in the crawler log and the failure URL list.
            throw e;
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
        }
        return StringUtil.EMPTY;
    }

    /**
     * Extracts the username from a file upload.
     *
     * @param client the Slack client for user lookups
     * @param file the file to extract username from
     * @return the username or empty string if not found
     * @throws SlackApiException if the user lookup failed with a fatal or transient Slack error;
     *             see {@link #getMessageUsername} for why this is not absorbed here
     */
    public String getFileUsername(final SlackClient client, final File file) {
        try {
            if (file.getUser() != null) {
                return getUsername(client, file.getUser());
            }
        } catch (final SlackApiException e) {
            throw e;
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to get a username from message.", e);
            }
        }
        return StringUtil.EMPTY;
    }

    /**
     * Retrieves a user's display name by user ID.
     *
     * @param client the Slack client for user lookups
     * @param userId the user ID to look up
     * @return the user's display name or the user ID if lookup fails
     * @throws SlackApiException if {@code users.info} failed with a fatal or transient Slack
     *             error. Falling back to the raw ID is right for a user who really is unknown and
     *             wrong for a revoked token: the crawl would keep indexing documents whose author
     *             is an ID rather than a name and still report success. The catch below therefore
     *             names only the three cache-miss types, deliberately leaving this one out --
     *             {@link SlackClient#load} has already unwrapped it out of Guava's
     *             {@link UncheckedExecutionException} so that exclusion actually takes effect.
     */
    protected String getUsername(final SlackClient client, final String userId) {
        try {
            final User user = client.getUser(userId);
            if (user != null) {
                final Profile profile = user.getProfile();
                if (profile != null) {
                    if (StringUtil.isNotBlank(profile.getDisplayName())) {
                        return profile.getDisplayName();
                    }
                    if (StringUtil.isNotBlank(profile.getRealName())) {
                        return profile.getRealName();
                    }
                }
                if (StringUtil.isNotBlank(user.getRealName())) {
                    return user.getRealName();
                }
                if (StringUtil.isNotBlank(user.getName())) {
                    return user.getName();
                }
            }
        } catch (final ExecutionException | UncheckedExecutionException | InvalidCacheLoadException e) {
            logger.warn("Failed to get username from user: {}", userId, e);
        }
        return userId;
    }

    /**
     * Extracts text content from message attachments.
     *
     * @param message the message containing attachments
     * @return the concatenated attachment text or empty string if no attachments
     */
    protected String getMessageAttachmentsText(final Message message) {
        final List<Attachment> attachments = message.getAttachments();
        if (attachments == null) {
            return StringUtil.EMPTY;
        }
        return attachments.stream().map(Attachment::getFallback).filter(StringUtil::isNotBlank).collect(Collectors.joining("\n"));
    }

    /**
     * Generates or retrieves the permalink URL for a message.
     *
     * @param client the Slack client
     * @param team the team information
     * @param channel the channel containing the message
     * @param message the message to get permalink for
     * @return the permalink URL for the message, an empty string if the message carries no
     *         timestamp to build one from, or {@code null} if {@code chat.getPermalink} had to be
     *         called (no {@code team}, i.e. {@code team.info} failed) and itself failed with a
     *         message-scoped error -- see {@link #processMessage} for the identifier substituted
     *         in that case
     * @throws SlackApiException if {@code chat.getPermalink} failed with a fatal or transient
     *             Slack error
     */
    public String getMessagePermalink(final SlackClient client, final Team team, final Channel channel, final Message message) {
        String permalink = message.getPermalink();
        if (permalink == null) {
            if (message.getTs() == null) {
                return StringUtil.EMPTY;
            }
            if (team == null) {
                permalink = client.getPermalink(channel.getId(), message.getTs());
            } else {
                permalink =
                        "https://" + team.getDomain() + ".slack.com/archives/" + channel.getId() + "/p" + message.getTs().replace(".", "");
            }
        }
        return permalink;
    }

    /**
     * Builds the indexed title for a file: its name and its Slack-assigned title, joined by a
     * space and each skipped when blank.
     *
     * @param file the file being indexed
     * @return the file name and title joined by a space
     */
    protected String getFileTitle(final File file) {
        return Stream.of(file.getName(), file.getTitle()).filter(StringUtil::isNotBlank).collect(Collectors.joining(" "));
    }

    /**
     * Builds the indexed text for a file: its name followed by its extracted content.
     *
     * @param file the file being indexed
     * @param fileContent the file's extracted content; {@link #getFileContent} never returns
     *            null, only {@link StringUtil#EMPTY} when extraction yields nothing
     * @return the file name and content joined by a newline, without a trailing newline when
     *         the content is blank
     */
    protected String getFileText(final File file, final String fileContent) {
        return Stream.of(file.getName(), fileContent).filter(StringUtil::isNotBlank).collect(Collectors.joining("\n"));
    }

    /**
     * Downloads and extracts content from a Slack file.
     *
     * <p>
     * Passes {@code file.getName()} to the extractor as a filename hint -- Tika uses it as an
     * additional signal when the declared MIME type is ambiguous or wrong -- and {@code
     * maxContentLength} as the extraction bound. See {@link #MAX_CONTENT_LENGTH} for how that
     * bound relates to {@link #MAX_FILESIZE}, the separate pre-download transfer bound already
     * checked in {@link #processFile} before this method is ever called.
     * </p>
     *
     * @param client the Slack client for file download
     * @param file the file to extract content from
     * @param maxContentLength the maximum content length to pass to the extractor; negative
     *            defers to {@code ContentLengthHelper}'s per-MIME-type limit (see {@link
     *            #getMaxContentLength})
     * @param ignoreError whether to ignore extraction errors
     * @return the extracted file content or empty string if extraction fails
     */
    protected String getFileContent(final SlackClient client, final File file, final long maxContentLength, final boolean ignoreError) {
        if (file.getPermalink() != null) {
            final String mimeType = file.getMimetype().trim();
            final String fileUrl = file.getUrlPrivateDownload();
            try (final CurlResponse response = client.getFileResponse(fileUrl)) {
                if (response.getHttpStatusCode() != 200) {
                    throw new SlackDataStoreException(
                            "HTTP Status " + response.getHttpStatusCode() + " : failed to get the file from " + fileUrl);
                }
                try (final InputStream in = response.getContentAsStream()) {
                    return ComponentUtil.getExtractorFactory()
                            .builder(in, null)
                            .filename(file.getName())
                            .mimeType(mimeType)
                            .extractorName(extractorName)
                            .maxContentLength(maxContentLength)
                            .extract()
                            .getContent();
                }
            } catch (final Exception e) {
                if (!ignoreError && !ComponentUtil.getFessConfig().isCrawlerIgnoreContentException()) {
                    throw new DataStoreCrawlingException(file.getPermalink(), "Failed to get contents: " + file.getName(), e);
                }
                if (logger.isDebugEnabled()) {
                    logger.warn("Failed to get contents: {}", file.getName(), e);
                } else {
                    logger.warn("Failed to get contents: {}. {}", file.getName(), e.getMessage());
                }
                return StringUtil.EMPTY;
            }
        }
        return StringUtil.EMPTY;
    }

}
