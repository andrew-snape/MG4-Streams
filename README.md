# MG4 Streams

Internet radio and IPTV player for the MG4 head unit (SAIC, Android 9, 1778 × 720 screen). It plays your own M3U/M3U8 playlists, keeps audio playing in the background, and shows **video only when the car is in P**.

Unofficial. Not affiliated with MG or SAIC. Use at your own risk.

## Features

- **Three playlists, one per tab:** Radio, TV and My list. Each is added from a URL, imported from a file, or set up from a text file of addresses on a USB stick. URLs are saved and updated once a day, or on demand.
- **Updates itself from GitHub:** checks this repo's latest release once a day and installs it from Settings.
- **Big, car-sized controls:** a now-playing panel with logo, name and song or programme, and a large Play/Stop button. A logo grid with favourites (long-press a station to add it).
- **Steering-wheel keys:** previous/next step through the list on screen, via the media session.
- **Takes over the audio:** starting a stream asks Android for audio focus, so the built-in FM/DAB tuner and Bluetooth audio stop.
- **TV guide (EPG):** read from the playlist's `x-tvg-url` (XMLTV, gzip or plain). Shows now/next for each channel.
- **Per-channel headers:** `#EXTVLCOPT:http-user-agent` / `http-referrer` and Kodi-style `URL|User-Agent=...` are sent on every request, including HLS segments.
- **Reconnects by itself:** waits for the network at car start (for example before a phone hotspot connects), and retries dropped or expired streams.
- **Remembers** the last station, with optional auto-resume on start.

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

Before installing, the app checks that the download is the same app, has a higher `versionCode`, and is **signed with the same key** as the installed copy. A platform-signed build installs without a prompt. Otherwise Android shows its install screen.

### Publishing a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build and sign as above.
3. Create a GitHub release whose tag matches the version and attach the signed APK:

   ```sh
   gh release create v0.2.0 MG4Streams-platform.apk --title "v0.2.0" --notes "What changed"
   ```

The app offers a release when its tag is higher than the installed `versionName` (`v0.3.0` > `0.2.0`), and only installs it if the APK's `versionCode` is also higher, so bump both. It uses the first `.apk` attached to the release.

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
| `Updater.kt` | GitHub release check, download, signature check, install |

## Licence

MIT. See [LICENSE](LICENSE). The licence covers this code only, not the head unit's software, the platform key, or any playlist content.
