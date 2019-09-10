Slack Data Store for Fess [![Build Status](https://travis-ci.org/codelibs/fess-ds-slack.svg?branch=master)](https://travis-ci.org/codelibs/fess-ds-slack)
==========================

## Overview

Slack Data Store is an extension for Fess Data Store Crawling.

## Download

See [Maven Repository](http://central.maven.org/maven2/org/codelibs/fess/fess-ds-slack/).

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
