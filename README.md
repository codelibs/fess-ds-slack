Slack Data Store for Fess [![Build Status](https://travis-ci.org/codelibs/fess-ds-slack.svg?branch=master)](https://travis-ci.org/codelibs/fess-ds-slack)
==========================

## Overview

Slack Data Store is an extension for Fess Data Store Crawling.

## Download

See [Maven Repository](http://central.maven.org/maven2/org/codelibs/fess/fess-ds-slack/).

## Installation
### For 13.2.x and older

1. Download fess-ds-slack-X.X.X.jar
2. Copy fess-ds-slack-X.X.X.jar to $FESS\_HOME/app/WEB-INF/lib or /usr/share/fess/app/WEB-INF/lib

### For 13.3 and later

You can also install the plugin on the administration (See [the Administration guide](https://fess.codelibs.org/13.3/admin/plugin-guide.html)).

1. Download fess-ds-slack-X.X.X.jar
2. Copy fess-ds-slack-X.X.X.jar to $FESS\_HOME/app/WEB-INF/plugin or /usr/share/fess/app/WEB-INF/plugin

## Getting Started

### Parameters
Example :
```
token=xoxp-************-************-************-********************************
channels=general,random
file_crawl=false
includePrivate=false
```

| Key | Value |
| --- | --- |
| token | OAuth Access Token of SlackApp with permissions. |
| channels | Scope of channels to crawl. (comma-separated or `*all`) |
| file_crawl | `true` or `false` (Crawl files or not.) |
| includePrivate |  `true` or `false` (Crawl private channels or not.)|

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