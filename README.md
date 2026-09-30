# Mini YouTube

An Android app that does one thing: it follows the YouTube channels you choose and puts
each new video in a backlog, with a notification when one lands. You watch it in the app
and mark it watched, and it leaves the backlog.

There are no recommendations, no Shorts, no home feed, no comments and no search. There
is no account either - nothing to sign in to, and nothing leaves the phone except requests
to YouTube itself.

Native Kotlin and Jetpack Compose, Android only.

## Following a channel

Open **Channels** (the icon top right of the backlog) and paste any of:

- a channel link - `youtube.com/@handle`, `/channel/UC...`, `/c/name`, `/user/name`
- a video link - `youtu.be/...`, `/watch?v=...`, `/shorts/...`, `/live/...` - which
  follows whoever uploaded it
- a bare `@handle` or `UC...` channel id

Or skip the pasting: in the YouTube app, open a channel or video, tap **Share**, and pick
**Follow in Mini YouTube**. The link arrives in the Channels screen and is followed straight away.

Following starts **from now**. The channel's existing videos are not added - only uploads
published after you follow it. Unfollowing removes the channel's videos from the backlog.

## The backlog

New videos appear newest first, with the channel name and how long ago each was
published. Tap one to play it; tap the tick to mark it watched, with an undo in case of
a mis-tap. Pull down to check for new videos by hand.

A watched video is gone from the backlog for good, even though it stays in the channel's
feed for a while - the app remembers what it has already shown you.

## Watching

Videos play in the app through YouTube's own embedded player, the same one websites use.
Fullscreen rotates to landscape. Leaving a video part way through remembers where you
were, to within ten seconds.

Some uploaders switch embedding off. Those videos show a message instead, and the
**YouTube** button opens the video in the YouTube app or the browser. That button is
always there if you would rather watch it there anyway.

The embedded player still shows YouTube's end screen, limited to videos from the same
channel. Switching it off entirely is not something the embed allows.

## Notifications

A background check runs roughly every hour while the phone has a connection, and posts
one notification per new video. Tapping it opens that video. Marking a video watched in
the app clears its notification.

Android decides when the check actually runs, and may defer it in battery saver or Doze,
so a notification can arrive late. Opening the app always checks as well, so nothing is
ever missed - only announced late.

On Android 13 and later the app asks for notification permission on first launch. If it
is refused, the backlog shows a banner linking to the setting.

## How new videos are found

Every YouTube channel publishes an Atom feed of its fifteen most recent uploads at
`youtube.com/feeds/videos.xml?channel_id=...`. That is all the app reads: no API key, no
quota, no scraping of the home page.

- **Shorts** are recognised because the feed links them to `/shorts/<id>` rather than
  `/watch?v=<id>`, and are dropped.
- **Anything published before you followed** is dropped, which is what stops a new
  follow filling the backlog with the channel's back catalogue.
- **Anything already known** is dropped, watched or not.

Two consequences of using the feed rather than the Data API:

- A channel that publishes more than fifteen videos between two checks loses the oldest
  of them. With hourly checks that takes an unusually busy channel.
- The feed cannot tell a scheduled premiere or live stream from an ordinary upload, so one
  can appear in the backlog before it has started. It plays once it goes live.

Resolving a `@handle` to a channel id reads the channel's page once, when you follow it.

## Your data

Everything is in one Room database on the phone: the channels you follow, and the videos
that have entered the backlog - watched ones included, since that is how they stay out.
Android's Auto Backup copies that database to your Google account; caches and thumbnails
are excluded. Turn it off in Android's own backup settings if you would rather it did not.

## Setup

Requires JDK 17 and the Android SDK (`ANDROID_HOME` set).

```powershell
.\gradlew.bat assembleDebug
```

The debug build installs as `com.miniyoutube.app.debug`, a separate app with its own
database, so it can be tried beside the release install without touching it. Installing
on a phone, and the release signing key, are covered in
[docs/INSTALLING.md](docs/INSTALLING.md).

## Development

```powershell
.\gradlew.bat staticAnalysis      # ktlint + detekt + Android lint (warnings are errors)
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest   # needs a device or emulator
```

Enable the pre-commit hook once per clone; it runs ktlint and detekt:

```powershell
git config core.hooksPath .githooks
```

CI runs all of the above on every push and pull request to `main`, and the
instrumentation tests on an emulator for pushes to `main`.

## Layout

```
app/src/main/java/com/miniyoutube/app/
  domain/    feed parsing, link parsing, which entries become backlog
  data/      Room entities and DAO, the refresher, following
  network/   the YouTube client: feed, channel page, oEmbed
  notify/    the hourly worker and its notifications
  ui/        backlog, channels, player
```

The logic worth testing is pure and lives in `domain/`; `data/FeedRefresher` is tested
against an in-memory store and `network/YouTubeClient` against a local HTTP server.
