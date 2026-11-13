# MG4 Streams

Internet radio and IPTV player for the MG4 head unit (SAIC, Android 9, 1778 × 720 screen). It plays your own M3U/M3U8 playlists, keeps audio playing in the background, and shows **video only when the car is in P**.

Unofficial. Not affiliated with MG or SAIC. Use at your own risk.

## Features

- **Three playlists, one per tab:** Radio, TV and My list. Each is added from a URL, imported from a file, or set up from a text file of addresses on a USB stick. URLs are saved and updated once a day, or on demand.
- **Updates from GitHub:** checks this repo's latest release once a day. Settings downloads it to Downloads; tap it there to install.
- **Big, car-sized controls:** a now-playing panel with logo, name and song or programme, and a large Play/Stop button. A logo grid with favourites (long-press a station to add it).
- **Steering-wheel keys:** previous/next step through the list on screen, via the media session.
- **Takes over the audio:** starting a stream asks Android for audio focus, so the built-in FM/DAB tuner and Bluetooth audio stop.
- **TV guide (EPG):** read from the playlist's `x-tvg-url` (XMLTV, gzip or plain). Shows now/next for each channel.
- **Per-channel headers:** `#EXTVLCOPT:http-user-agent` / `http-referrer` and Kodi-style `URL|User-Agent=...` are sent on every request, including HLS segments.
- **Reconnects by itself:** waits for the network at car start (for example before a phone hotspot connects), and retries dropped or expired streams.
- **Driving mode:** whenever the car isn't in P, the grid, search and settings disappear. Six big presets (favourites, then recently played), larger text and buttons. Previous/next step through the presets. Swipe the logo to change station.
- **Parked extras:** 640 × 360 video, group chips for TV, a programme progress bar, 3 or 5 column grid, Favs / Recent / All.
- **Connection chip and Retry:** shows Car internet / Wi-Fi / Offline / Reconnecting; a Retry button after a failure, with a hint when a channel blocks the car's internet.
- **Data use:** today and this month, by connection, in Settings.
- **Arrange presets:** Settings > Arrange presets sets the order of favourites; the first six are the driving presets.
- **Skips dead stations:** a station that fails today on the current connection is greyed out and skipped by previous/next (screen and steering wheel). Tapping it still tries it. Playing it successfully clears it.
- **Album art:** for songs ("Artist - Song" stream titles) the cover is looked up on Apple's iTunes Search and shown instead of the logo, and in the media notification. Only the song title is sent. Off switch in Settings.
- **Remembers** the last station and resumes it on start (on by default; never TV). Night dim from 7 pm to 6 am.

## Safety

- Video plays only when the gear reads **P**. It fails closed: an unknown, unreadable or stale gear reading means no video. Audio is never blocked.
- Search and Settings, which need the keyboard, also only open in P.
- Car access is **read-only**: one Binder lookup on the SAIC vehicle hub (`vehiclecondition`) and one gear read. See `SaicGearGate.kt`. Nothing is written to the car.

## Playlists

Open Settings (car in P), choose **Radio**, **TV** or **My list**, then:

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

Formats: whatever the head unit's Android decoders handle. H.264 and AAC play. AC3 audio and AV1 video generally don't.

## Build

Requirements: JDK 17, Android SDK (compileSdk 34). Create `local.properties` with `sdk.dir=/path/to/Android/sdk`, then run:

```sh
./gradlew assembleDebug testDebugUnitTest
```

## Sign and install

The app is installed and tested signed with the head unit's platform key (`platform.pk8` and `platform.x509.pem`). **It is not included here and must never be committed.**

```sh
apksigner sign --key platform.pk8 --cert platform.x509.pem \
  --out MG4Streams-platform.apk app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs MG4Streams-platform.apk
```

Install over ADB. `adb install` is refused, so push the APK and install it with `pm`:

```sh
adb push MG4Streams-platform.apk /data/local/tmp/
adb shell pm install -r -f -d -t /data/local/tmp/MG4Streams-platform.apk
```

Updates must be signed with the same key.

## App updates

Settings > **Check for app updates** looks at the latest release of this repo (set in `app/build.gradle.kts` as `UPDATE_REPO`; change it in a fork). The app also checks quietly once a day and shows a message when there's a new version.

The head unit refuses installs started by the app itself ("not a system app"), so the app downloads the APK with DownloadManager into **Downloads** (`MG4Streams-<version>.apk`), checks it, then opens Downloads. Tap the file and choose Install. The first time, the app asks for storage permission so the file goes in the public Downloads folder; without it, the file goes in the app's own folder (whether the Downloads screen lists it there is still to check on the car).

Before opening Downloads, the app checks that the file is the same app, has a higher `versionCode`, and is **signed with the same key** as the installed copy. A file that fails is deleted.

### Publishing a release

```sh
scripts/release.sh 0.5.0                 # bump, test, build (R8, ~2.7 MB), platform-sign, check cert, ask, publish
scripts/release.sh 0.5.0 --no-publish    # stop after dist/MG4Streams-0.5.0-platform.apk
```

The script expects the platform key in `~/Documents/GitHub/DiPlay-MG4/tools` and refuses to continue if the signing certificate isn't the one the head unit has. Release notes come from `dist/notes-<version>.md`, or one line typed at the prompt. It asks before running `gh release create`.

The release build uses R8 (`isMinifyEnabled`, `isShrinkResources`): unused code from Media3, Guava and Kotlin, and unused resources, are removed. 14 MB debug, about 2.7 MB release.

## Code layout

`app/src/main/java/com/mg4/streams/`:

| File | Role |
|---|---|
| `MainActivity.kt` | Screen, playlists, controller connection, video gate |
| `PlaybackService.kt` | Media3 `MediaSessionService` that owns ExoPlayer: background audio, audio focus, retries |
| `StreamMediaSourceFactory.kt` | Per-channel HTTP headers on every request |
| `SaicGearGate.kt` / `ParkedGate.kt` | Read-only gear check over Binder |
| `M3uParser.kt` | Playlist parser |
| `Epg.kt` | XMLTV guide download, filter and cache |
| `StationLists.kt`, `Favourites.kt`, `LogoLoader.kt` | List filtering, favourites, logo cache |
| `UrlListParser.kt` | Address-list `.txt` import |
| `DataUsage.kt`, `Net.kt` | Data-use counter by connection; connection type |
| `Updater.kt` | GitHub release check, download to Downloads, signature check |
| `NetCheck.kt` | Settings > Network check: connection, public address, and a step-by-step stream test |

## Licence

MIT. See [LICENSE](LICENSE). The licence covers this code only, not the head unit's software, the platform key, or any playlist content.
