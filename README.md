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
```

| Key | Value |
| --- | --- |
| message.text | Text contents of the Message. |
| message.user | User(display name) of the Message. |
| message.channel | Channel name the Message sent. |
| message.timestamp | Timestamp the Message sent. |
| message.permalink | Permalink of the Message. |
| message.attachments | Fallback of attachments of the Message. |
