# MG4 Streams

Internet radio and IPTV player for the MG4 head unit (SAIC, Android 9, 1778 × 720 screen). It plays your own M3U/M3U8 playlists, keeps audio playing in the background, and shows **video only when the car is in P**.

Unofficial. Not affiliated with MG or SAIC. Use at your own risk.

## Features

- **Three playlists, one per tab:** Radio, TV and My list. Each is added from a URL or imported from a file (USB stick). URLs are saved and updated once a day, or on demand.
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
- **Update all playlists:** re-downloads every tab that has a saved URL.

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

## Licence

MIT. See [LICENSE](LICENSE). The licence covers this code only, not the head unit's software, the platform key, or any playlist content.
