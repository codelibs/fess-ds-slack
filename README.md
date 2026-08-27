Slack Data Store for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-ds-slack/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-ds-slack/actions/workflows/maven.yml)
==========================

## Overview

Slack Data Store is an extension for Fess Data Store Crawling.

## Download

See [Maven Repository](https://repo1.maven.org/maven2/org/codelibs/fess/fess-ds-slack/).

## Installation

See [Plugin](https://fess.codelibs.org/13.3/admin/plugin-guide.html) of Administration guide.

## Getting Started

### Parameters
Example :
```
token=xoxp-************-************-************-********************************
channels=general,random
file_crawl=false
include_private=false
```

| Key | Value |
| --- | --- |
| token | OAuth Access Token of SlackApp with permissions. |
| channels | Scope of channels to crawl. (comma-separated or `*all`) |
| file_crawl | `true` or `false` (Crawl files or not.) |
| include_private |  `true` or `false` (Crawl private channels or not.)|

### Advanced Parameters

The parameters below are the ones this plugin adds or that are worth calling out. They are not
the complete set: the common data store parameters Fess itself defines (`number_of_threads`,
`max_filesize`, `supported_mimetypes`, `ignore_error`, `include_pattern`, `exclude_pattern`,
`proxy_host`, `proxy_port`, and the per-request page-size and cache-size settings) are also
accepted. See the Slack data store page in the Fess documentation for the full list.

| Key | Default | Value |
| --- | --- | --- |
| exclude_archived | `false` | `true` or `false` (Exclude archived channels from `conversations.list`.) |
| ignore_system_events | `true` | `true` or `false` (Exclude Slack-generated channel-administration messages -- `channel_join`, `channel_topic`, `pinned_item`, etc. -- from indexing.) **Changed from earlier releases**: this defaults to `true`, so an existing configuration that upgrades and never sets this parameter will index fewer documents than before, with no error or warning. Set it to `false` to keep indexing these messages. |
| read_interval | `0` (no pacing) | Milliseconds to sleep after each top-level message and each file, to pace the crawl against a rate-limited workspace. Thread replies fetched via `conversations.replies` are not individually paced. |
| connection_timeout | `20000` | Connection timeout, in milliseconds, for each Slack API request. |
| read_timeout | `20000` | Read timeout, in milliseconds, for each Slack API request. |
| max_retry_count | `3` | Maximum number of retries for a request that receives a `429` (rate limited) or `5xx` response. |
| retry_interval | `3000` | Wait, in milliseconds, before the first retry when the response carries no `Retry-After` header. Doubles with each further attempt, capped at `60000`. A `Retry-After` header is honoured instead, and is capped at `60000` too. |
| executor_timeout | `60` | Seconds `storeData` waits for queued crawl work to finish before forcing shutdown. |
| max_content_length | unset (defer to Fess's per-MIME-type limit) | Maximum size, in bytes, a downloaded file may have before text extraction is skipped for it. This is a size check on the bytes actually received, not a truncation limit: an over-size file is skipped whole. See also `max_filesize`, which applies the same kind of bound to the size Slack reports, before the download. |

### Error Handling

A Slack API call that fails outright -- the token itself can no longer authenticate, or a rate
limit / server error survives every retry -- fails the whole crawl job instead of silently
indexing a partial or empty result as a "success". A failure scoped to one channel or one page
(for example a channel the token cannot see) is instead warned about and skipped, and the crawl
continues with the next channel. See the `SlackDataStore` class javadoc in the source for the
full mechanism.

### Scripts 
Example :
```
title=message.user + " #" + message.channel
digest=message.text + "\n" + message.attachments
content=message.text
created=message.timestamp
timestamp=message.timestamp
url=message.permalink
role=message.roles
```

| Key | Value |
| --- | --- |
| message.text | Text contents of the Message. |
| message.user | User(display name) of the Message. |
| message.channel | Channel name the Message sent. |
| message.timestamp | Timestamp the Message sent. |
| message.permalink | Permalink of the Message. |
| message.attachments | Fallback of attachments of the Message. |
| message.roles | Search roles allowed to see this document. Only present when `permission_sync=true` (see below); a script that omits `role=message.roles` never applies them, so the document is indexed exactly as unrestricted as if `permission_sync` were off. |

### Permission Synchronisation (ACL)

| Key | Default | Value |
| --- | --- | --- |
| permission_sync | `false` | `true` or `false`. When `true`, each private channel's membership is resolved into search roles (one per member, by email) so its content is searchable only by that channel's members. A channel whose membership cannot be reliably resolved is skipped entirely for that crawl (fail-closed) rather than indexed without a working access control list. |
| default_permissions | unset | Comma-separated list of additional permissions, in the admin UI's `{user}name` / `{group}name` / `{role}name` syntax, applied to every document regardless of channel membership. |

**`permission_sync` only computes roles; it does not apply them.** The computed roles are exposed
to crawl scripts as `message.roles` (see the Scripts table above) -- your script must map
`role=message.roles` for them to take effect. Without that mapping, `permission_sync=true` costs
API calls and can skip private channels that fail role resolution, while providing none of the
access control it promises.

**Required Slack OAuth scope**: resolving a member's email requires the `users:read.email` scope
on the token. Without it, `Profile#getEmail()` returns `null` for every member, so a private
channel with members still fails closed (see the `users:read.email` warning in the log) instead
of being indexed unrestricted.

**The Fess principal name must equal the Slack email, and lowercase.** Search-time roles come
from `principal.getName()` (the Fess login name) with no normalisation; the roles this feature
computes come from each member's Slack email. Slack itself normalises email addresses to
lowercase, so if Fess login names are not also lowercase, the two will never match for any user
with an uppercase character in their login name. This fails closed -- a mismatched user simply
sees no results, not someone else's private content -- but it presents as "search silently
returns nothing" for private-channel content, which is easy to mistake for an unrelated bug.
Keep Fess login names lowercase to avoid this.

**Enabling this feature does not retroactively secure an already-indexed workspace.** Documents
indexed by an earlier `permission_sync=false` crawl remain unrestricted in the index; nothing
removes or re-indexes them automatically. A full re-crawl with `permission_sync=true` (and a
script that maps `role=message.roles`) is required to apply roles to previously-indexed content.
Likewise, turning `permission_sync` back off does not restore any restriction on the next crawl.
