# Stack

Native Kotlin + Jetpack Compose, targeting Android only. Set up the same way as the
sibling `habit_tracker` and `show_tracker`: the same Gradle version catalog, ktlint +
detekt 2 (type-resolving `detektMain`/`detektTest`, not the bare `detekt` task) + Android
lint with `warningsAsErrors`, all behind `./gradlew staticAnalysis`, and the same
absent-tolerant `keystore.properties` signing block.

- **Persistence** - Room via KSP. Schemas are committed under `app/schemas`; migrations
  are verified by `MigrationTest`. Destructive fallback is never enabled.
- **Network** - plain OkHttp to three YouTube endpoints, none needing a key: the channel
  Atom feed (scheduled), the channel page (once, on follow, for the `UC...` id behind a
  handle) and oEmbed (once, on follow from a video link).
- **Background** - one WorkManager periodic job, hourly, network-constrained, KEEP.
- **Playback** - `com.pierfrancescosoffritti.androidyoutubeplayer:core`, YouTube's IFrame
  embed in a WebView. It sets the HTTP referrer the embed has required since mid-2025.

## Things that are not obvious from the code

- **Shorts are only identifiable by the feed entry's link** (`/shorts/<id>`). The
  `UULF`-prefixed "long-form uploads" playlist feed that used to exclude them returns 404
  as of 2026-09, which was checked before building on the channel feed.
- **A watched video is never deleted.** Its row is what stops the next check re-adding
  it while it is still among the feed's fifteen entries. `newArrivals` also drops anything
  published before `followedAt`, which is what makes "follow from now" work.
- **`followedAt` is YouTube's clock** (the feed response's `Date` header), because it is
  compared with publish times YouTube stamped. The phone clock is only the fallback.
- **Premieres and scheduled streams** are in the feed from scheduling, with `views="0"`.
  Only zero-view entries cost a watch-page fetch (~1.2 MB); `isUpcoming` is read from the
  `videoDetails` object alone, cut out by brace matching, because the sidebar carries the
  same field for other videos. A pending one is stored with `availableAt` set - known, so
  not re-fetched, but out of the backlog - and `FeedRefresher.releaseDue` re-checks it
  once the time comes, since premieres get postponed.
- **Overflow backfill** reads `/channel/<id>/videos` (30 newest long-form uploads) when
  `feedOverflowed` says the feed rolled past `channels.feedHighWater`. The walk stops at
  the first known id, and each candidate costs a watch-page fetch for its exact date.
- **The feed endpoint goes down for every channel at once** (404s, e.g. 2026-10-01, while
  `/channel/<id>/videos` and oEmbed kept working). A `YouTubeException` from the feed -
  YouTube answered - makes `FeedRefresher` check from the videos tab instead, and
  `Follower` take `followedAt` from that tab's `Date` header. A plain `IOException` - no
  answer - does not fall back, since the tab would fail the same way.
- **Never `REPLACE` into `channels`.** SQLite's REPLACE is delete-then-insert, and the
  delete cascades to `videos`, silently forgetting what was watched. Inserts are IGNORE.
- **TRUNCATE journal, not WAL**, so Auto Backup's copy of the single `.db` file is always
  complete. show_tracker needed a backup agent to checkpoint WAL for the same reason.
- **`MainActivity` declares `configChanges` for orientation.** Fullscreen rotates to
  landscape, and recreating the activity would tear down the player's WebView and restart
  the video.
- **The backlog and channels ViewModels are activity-scoped** in the nav host: the
  player's "Mark as watched" goes through the backlog's so its undo snackbar shows where
  the user lands, and a shared link starts following before the channels screen exists.
- **Requests send `Cookie: SOCS=CAI`** to skip the EU consent interstitial, whose page
  carries no channel id.
- `assembleDebug` and `testDebugUnitTest` do not compile `src/androidTest`. Run
  `:app:assembleDebugAndroidTest` before pushing; it needs no device.
- The debug build is `com.miniyoutube.app.debug` with its own database, so
  `connectedDebugAndroidTest` never touches the release install.
