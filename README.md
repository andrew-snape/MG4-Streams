# MG4 Streams

Internet radio, IPTV and USB video player for the MG4 head unit (SAIC, Android 9, 1778 × 720 screen). It plays your own M3U/M3U8 playlists and TV shows from a USB stick, keeps audio playing in the background, and shows **video only when the car is in P**.

Unofficial. Not affiliated with MG or SAIC. Use at your own risk.

## Features

- **USB TV shows:** a USB tab browses the stick by folder (Show / Season 1 / episodes), sorted naturally (E2 before E10). Episode names come from the file name (`S01E02`, `1x02`, `Show - S01E02 - Title`) or a Kodi/Plex `.nfo`; posters from `poster.jpg`/`folder.jpg`, otherwise a still from the video. Each episode remembers where you stopped and shows a progress bar; finished ones get a ✓ (long-press to toggle). The next episode plays automatically, into the next season. **Continue** tiles at the top resume unfinished episodes. `.srt`/`.ass`/`.vtt` subtitles next to the video load automatically. Nothing is written to the stick.
- **Three playlists, one per tab:** Radio, TV and My list. Each is added from a URL, imported from a file, or set up from a text file of addresses on a USB stick. URLs are saved and updated once a day, or on demand.
- **Updates from GitHub:** checks this repo's latest release once a day. Settings downloads it to Downloads; tap it there to install.
- **Big, car-sized controls:** a now-playing panel with logo, name and song or programme, and a large Play/Stop button. A logo grid with favourites (long-press a station to add it).
- **Steering-wheel keys:** previous/next step through the list on screen, via the media session.
- **Takes over the audio:** starting a stream asks Android for audio focus, so the built-in FM/DAB tuner and Bluetooth audio stop.
- **TV guide (EPG):** read from the playlist's `x-tvg-url` (XMLTV, gzip or plain). Shows now/next for each channel.
- **Per-channel headers:** `#EXTVLCOPT:http-user-agent` / `http-referrer` and Kodi-style `URL|User-Agent=...` are sent on every request, including HLS segments.
- **Reconnects by itself:** waits for the network at car start (for example before a phone hotspot connects), and retries dropped or expired streams.
- **Driving:** the full station grid, tabs, search and Settings work in any gear; only video needs P. Optional **Simple driving screen** (Settings): when not in P, six big presets (favourites, then recently played), larger text and buttons, and previous/next step through the presets. Swipe the logo to change station.
- **Parked extras:** 640 × 360 video, group chips for TV, a programme progress bar, 3 or 5 column grid, Favs / Recent / All.
- **Connection chip and Retry:** shows Car internet / Wi-Fi / Offline / Reconnecting; a Retry button after a failure, with a hint when a channel blocks the car's internet.
- **Data use and Network check:** data used today and this month, by connection, in Settings. Network check tests the connection and the station on screen step by step (redirects, playlist, segments) and shows the HTTP codes.
- **Arrange presets:** Settings > Arrange presets sets the order of favourites; the first six are the presets on the Simple driving screen.
- **Skips dead stations:** a station that fails today on the current connection is greyed out and skipped by previous/next (screen and steering wheel). Tapping it still tries it. Playing it successfully clears it.
- **Album art:** for songs ("Artist - Song" stream titles) the cover is looked up on Apple's iTunes Search and shown instead of the logo, and in the media notification. Only the song title is sent. Off switch in Settings.
- **Remembers** the last station and resumes it on start (on by default; never TV). Night dim from 7 pm to 6 am.

## Safety

- Video plays only when the gear reads **P**. It fails closed: an unknown, unreadable or stale gear reading means no video. Audio is never blocked.
- Car access is **read-only**: one Binder lookup on the SAIC vehicle hub (`vehiclecondition`) and one gear read. See `SaicGearGate.kt`. Nothing is written to the car.

## Playlists

Open Settings, choose **Radio**, **TV** or **My list**, then:

- **Add URL:** downloads the playlist into that tab and saves the address for updates.
- **Import file:** loads an `.m3u`/`.m3u8` file into that tab.
- **Clear list:** empties that tab.
- **Import address list (.txt):** sets up several tabs at once from a text file (below).
- **Update all playlists:** re-downloads every tab that has a saved URL.

### Address list file

Put a `.txt` file on a USB stick and choose **Import address list**. Each line names a tab (`radio`, `tv`, `my list`) or sits under a `[tab]` header. A tab can have several playlists, which are joined together. Tabs the file doesn't mention are left alone. Addresses before any header go to the tab selected in Settings. Addresses under an unknown header are skipped. Anything after an address on the same line is ignored. Example ([docs/streams-example.txt](docs/streams-example.txt)):

```
# comments start with #
radio = https://example.com/radio.m3u8
tv = https://example.com/tv.m3u8

[my list]
https://example.com/my-playlist-1.m3u
https://example.com/my-playlist-2.m3u
```

Only `http://` and `https://` entries are kept. Supported tags: `#EXTINF` (name, `group-title`, `tvg-logo`, `tvg-id`/`channel-id`), `#EXTVLCOPT`, and `x-tvg-url`/`url-tvg` in the `#EXTM3U` header.

Formats: whatever the head unit decodes, plus Dolby and DTS audio through the built-in FFmpeg decoder.

### USB video formats

The head unit (MediaTek MT2712) decodes in hardware: **H.264, HEVC (H.265), VP9, VP8, MPEG-4 Part 2 (DivX/Xvid), MPEG-2**. Not AV1. Audio: AAC, MP3, Opus, Vorbis, FLAC on the head unit, and **AC3, E-AC3 (DD+), DTS and TrueHD** through the app's FFmpeg decoder (5.1 is mixed down to stereo).

Files: MP4, M4V, MOV, MKV, WebM, TS/M2TS, AVI, FLV, MPG, 3GP. Not WMV or RM. Best: H.264 or HEVC up to 1080p in MP4 or MKV. 4K and 10-bit HEVC are untested.

Suggested layout:

```
TV/
  Bluey/
    poster.jpg
    Season 1/
      Bluey - S01E01 - Magic Xylophone.mp4
      Bluey - S01E01 - Magic Xylophone.srt
```

## Build

Requirements: JDK 17, Android SDK (compileSdk 34). Create `local.properties` with `sdk.dir=/path/to/Android/sdk`, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The release build uses R8 (`isMinifyEnabled`, `isShrinkResources`) to remove unused code from Media3, Guava and Kotlin, and unused resources: about 5.3 MB (2.6 MB of it is the FFmpeg audio decoder), much smaller than the debug build. It comes out unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`) and is signed with the platform key afterwards.

GitHub Actions runs the same checks on every push to `main` and on pull requests (`.github/workflows/android.yml`), plus `scripts/check_public_tree.py`, which fails if a key file, an APK or an iCloud duplicate (`name 2.kt`) is committed.

## Sign and install

The app is installed and tested signed with the head unit's platform key (`platform.pk8` and `platform.x509.pem`). **It is not included here and must never be committed.**

```sh
apksigner sign --key platform.pk8 --cert platform.x509.pem \
  --out MG4Streams-platform.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs MG4Streams-platform.apk   # SHA-256 must be c8a2e9bc…92ab8
```

Install over ADB. `adb install` is refused, so push the APK and install it with `pm`:

```sh
adb push MG4Streams-platform.apk /data/local/tmp/
adb shell pm install -r -f -d -t /data/local/tmp/MG4Streams-platform.apk
```

Updates must be signed with the same key. After the first install, use the in-app updater (below).

## App updates

Settings > **Check for app updates** looks at the latest release of this repo (set in `app/build.gradle.kts` as `UPDATE_REPO`; change it in a fork). The app also checks quietly once a day and shows a message when there's a new version.

The head unit refuses installs started by the app itself ("not a system app"), so the app downloads the APK with DownloadManager into **Downloads** (`MG4Streams-<version>.apk`), checks it, then opens Downloads. Tap the file and choose Install. The first time, the app asks for storage permission so the file goes in the public Downloads folder; without it, the file goes in the app's own folder (whether the Downloads screen lists it there is still to check on the car).

Before opening Downloads, the app checks that the file is the same app, has a higher `versionCode`, and is **signed with the same key** as the installed copy. A file that fails is deleted.

### Publishing a release

Releases are built, signed and published by GitHub Actions (`.github/workflows/release.yml`) when a `v*` tag is pushed:

```sh
scripts/release.sh 0.5.2 "What changed"
```

The script checks you're on an up-to-date `main` with nothing uncommitted, bumps `versionName` and `versionCode`, runs the tests and lint, then asks before it commits `Release 0.5.2`, tags `v0.5.2` (the tag message becomes the release notes) and pushes both. The workflow checks the tag matches `versionName`, builds the release APK, signs it with the platform key from the repository secrets, refuses any certificate other than the head unit's, and publishes `MG4Streams-0.5.2-platform.apk`. Follow it with `gh run watch` or in the Actions tab. It can also be started by hand from the Actions tab (Release > Run workflow, with the tag).

Repository secrets (Settings > Secrets and variables > Actions), the same ones DiPlay-MG4 uses:

| Secret | Value |
|---|---|
| `MG4_PLATFORM_PK8_BASE64` | `base64 < platform.pk8` |
| `MG4_PLATFORM_CERT_BASE64` | `base64 < platform.x509.pem` |

To build and publish from a Mac instead (platform key in `~/Documents/GitHub/DiPlay-MG4/tools`):

```sh
scripts/release.sh 0.5.2 "What changed" --local               # build, sign, check, publish with gh
scripts/release.sh 0.5.2 --local --no-publish                 # only dist/MG4Streams-0.5.2-platform.apk
```

## Code layout

`app/src/main/java/com/mg4/streams/`:

| File | Role |
|---|---|
| `MainActivity.kt` | Screen, playlists, controller connection, video gate |
| `PlaybackService.kt` | Media3 `MediaSessionService` that owns ExoPlayer: background audio, audio focus, retries, FFmpeg audio (Dolby/DTS), USB resume points |
| `StreamMediaSourceFactory.kt` | Per-channel HTTP headers on every request |
| `SaicGearGate.kt` / `ParkedGate.kt` | Read-only gear check over Binder |
| `M3uParser.kt` | Playlist parser |
| `Epg.kt` | XMLTV guide download, filter and cache |
| `StationLists.kt`, `Favourites.kt`, `LogoLoader.kt` | List filtering, favourites, logo cache |
| `UrlListParser.kt` | Address-list `.txt` import |
| `DataUsage.kt`, `Net.kt` | Data-use counter by connection; connection type |
| `Updater.kt` | GitHub release check, download to Downloads, signature check |
| `NetCheck.kt` | Settings > Network check: connection, public address, and a step-by-step stream test |
| `DeadStations.kt` | Stations that failed today on this connection, skipped by previous/next |
| `UsbLibrary.kt`, `UsbPositions.kt` | USB sticks: folders, natural sort, episode names, `.nfo`, posters, subtitles; resume points and watched marks |
| `AlbumArt.kt`, `ArtworkPlayer.kt` | iTunes cover lookup; adds the cover to the session's metadata without reloading the stream |

`scripts/release.sh` (releases), `scripts/check_public_tree.py` (CI source check), `.github/workflows/` (checks and releases).

## Licence

MIT. See [LICENSE](LICENSE). The licence covers this code only, not the head unit's software, the platform key, or any playlist content.
