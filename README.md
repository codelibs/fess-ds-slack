Slack Data Store for Fess [![Build Status](https://travis-ci.org/codelibs/fess-ds-slack.svg?branch=master)](https://travis-ci.org/codelibs/fess-ds-slack)
==========================

## Overview

Slack Data Store is an extension for Fess Data Store Crawling.

## Download

See [Maven Repository](http://central.maven.org/maven2/org/codelibs/fess/fess-ds-slack/).

## Installation

1. Download fess-ds-slack-X.X.X.jar
2. Copy fess-ds-slack-X.X.X.jar to $FESS\_HOME/app/WEB-INF/lib or /usr/share/fess/app/WEB-INF/lib

## Getting Started

### Parameters

```
token=xoxp-************-************-************-********************************
channels=general,random
```

| Key | Value |
| --- | --- |
| token | OAuth Access Token of SlackApp with permissions. |
| channels | Scope of channels to crawl. (comma-separated or `*all`) |

### Scripts

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