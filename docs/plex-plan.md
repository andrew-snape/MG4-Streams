# MG4 Streams: Plex plan

Written 2026-11-17. Goal: a **Plex** tab that browses your Plex TV (and later music) libraries, plays on home Wi-Fi or over the car's internet or a hotspot, and syncs watched progress with Plex. Video only plays in P, as now.

## What we reuse

**From this app (most of the work):** the USB tab's Show → Season → Episode browsing, Continue tiles, progress bars, ✓ watched, next-episode autoplay, subtitles, the FFmpeg decoder for AC3/DTS, the P-only video gate, dropping to the lowest bitrate when you leave P (as live TV does), the data-use counter, Network check, and `LogoLoader` for posters.

**From Rivulet-Mac (knowledge, not code):** it's Swift, so nothing ports directly. Its licence is PolyForm Noncommercial and most of it was written by others, while this repo is MIT. So we write the Kotlin from scratch and reuse only the behaviour it has already worked out, which are facts about the Plex API:

| Rivulet-Mac file | What we take |
|---|---|
| `PlexNetworkManager.swift` `requestPin` / `checkPinAuthentication` / `getServers` | PIN sign-in flow, the `X-Plex-*` headers, `resources?includeHttps=1&includeRelay=1` |
| `PlexAuthManager.swift` `findBestConnection` / `connectionScore` | Which address to use: try the last one that worked first; skip loopback; local before remote, relay last; probe all of them at once and choose in that order; Docker bridge addresses after real LAN ones |
| `PlexRelay.swift` | Relay = `*.plex.direct:8443`. Direct play fails through the relay (HTTP 500), so a relay server always transcodes at 1.5 Mbps 480p or lower |
| `StreamingQuality.swift` / `QualityDecision.swift` | The bitrate steps (20 / 12 / 8 / 4 / 2 / 1.5 / 0.72 Mbps with resolutions); separate Home and Away settings; play the original if its bitrate fits under the cap, otherwise transcode; step down after stalls |
| `buildHLSTranscodeURL`, `stopTranscodeSession` | Transcode parameters (`path`, `protocol=hls`, `maxVideoBitrate`, `videoResolution`, `session`, `offset`, …); stop the transcode when playback ends |
| `/:/timeline`, `/:/scrobble`, `MacPlexPlayback.swift` | The timeline must start with `playing`; send it every 10 s and on pause/stop (keep sending every 10 s while paused); send stopped, then scrobble past about 90 %; if direct play never starts, retry once as a transcode |
| `continueWatchingURL` | Use `/hubs/continueWatching`, not `/library/onDeck`; `X-Plex-Container-Size` only works if `X-Plex-Container-Start` is sent too |
| `MacPlexSession+Playback.swift` `nextEpisode` | Next episode: the next child of the season, then the first episode of the next season |

## Phases and models

The model column is the Claude model to run each phase with. **Opus 5.5** for design and the risky parts (connections, playback in `PlaybackService`, the P gate). **Sonnet 5.5** for well-defined code and tests. **Haiku 5.5** for docs and release notes.

| # | Phase | Model | Size | Needs the car for |
|---|---|---|---|---|
| 0 | Feasibility check | Sonnet | S | That a direct-play MKV and a transcoded HLS play |
| 1 | `PlexClient`: sign-in, servers, connection choice | Opus (connections), Sonnet (rest) | M | Sign-in at plex.tv/link; home Wi-Fi vs hotspot address choice |
| 2 | Plex tab: browsing | Sonnet, Opus to review the `MainActivity` refactor | M–L | Scrolling, poster speed |
| 3 | Playback and quality | Opus | L | Direct play at home, transcode away, relay, leaving P |
| 4 | Progress sync, watched, next episode | Sonnet, Opus to review | M | Resume carries over to the TV and back |
| 5 | Plex music (optional) | Sonnet | M | Steering-wheel keys, driving screen |
| 6 | Network check, docs, release | Sonnet (Network check), Haiku (docs) | S | One Network check run |

### Phase 0: feasibility check (half a day)

Before building UI, prove the two risky parts on the head unit.

- On the Mac, with `curl`: get a token (PIN flow), call `resources`, and note the server's local, remote and relay URIs. Check that the local `https://<ip-dashes>.<hash>.plex.direct:32400` address resolves on home Wi-Fi. Some routers block this (DNS rebinding protection).
- A debug-only Settings entry that plays one hard-coded direct-play URL and one transcode URL in ExoPlayer.
- **Stop here if** neither plays.
- **Cleartext:** `targetSdk 28` blocks plain `http://`. Use plex.direct HTTPS everywhere, including on the LAN. Allow `http://192.168…:32400` only if the router blocks plex.direct, which needs a `network_security_config`.

### Phase 1: PlexClient (new `PlexClient.kt`, `PlexAuth.kt`)

- Headers on every request: `X-Plex-Client-Identifier` (a random UUID saved once), `X-Plex-Product=MG4 Streams`, `X-Plex-Version`, `X-Plex-Platform=Android`, `X-Plex-Device=MG4`, `Accept: application/json`. Parse with `org.json`, which Android already has, so no new dependency.
- **Sign-in:** `POST plex.tv/api/v2/pins?strong=false` gives a 4-character code. Show it big, with "Go to plex.tv/link". Check `GET pins/{id}` every 2 s for up to 10 minutes. Save the token in private SharedPreferences. Sign out clears it.
- **Servers:** `GET plex.tv/api/v2/resources?includeHttps=1&includeRelay=1`, keeping entries that `provides` `server`. Choose one server; offer a picker only if there's more than one. Shared servers use their own `accessToken`.
- **Connection choice** (`PlexConnections.kt`, pure Kotlin with unit tests): try the last one that worked first; otherwise probe `GET /identity` on every address at once (3 s timeout) and choose in this order: local → remote plex.direct → relay. Choose again when the network changes (`NetKind` changes, which the app already detects), so pulling out of the driveway switches from home Wi-Fi to the hotspot.
- Tests: JSON samples for pins, resources and connection scoring.

### Phase 2: Plex tab (browsing)

- Add `Tab.PLEX`, shown only after sign-in.
- **Refactor first:** turn `UsbRow` / `UsbAdapter` into a row that doesn't care where it came from (label, poster, progress, watched, and a key that can be a file or a Plex `ratingKey`). Then USB and Plex share the adapter, the progress bar, ✓ and the Continue section. This is the riskiest change to `MainActivity` (2,062 lines), so it goes in a commit of its own, with a check that USB still works.
- Levels: libraries (`/library/sections`, `show` type; plus `artist` in phase 5) → shows (`/library/sections/{id}/all`, pages of 100) → seasons → episodes (`/library/metadata/{id}/children`). **Continue** row from `/hubs/continueWatching?X-Plex-Container-Start=0&X-Plex-Container-Size=6`.
- **Posters:** `/photo/:/transcode?width=W&height=H&minSize=1&url=<thumb>&X-Plex-Token=…` at the tile's size, cached by `LogoLoader`. Small images keep car data down.
- Browsing works in any gear, as USB does. Offline or no server: a clear message in the tab, not an empty grid.

### Phase 3: playback and quality

- **Settings:** *Plex quality at home* (default Original) and *away* (default 4 Mbps 720p), using Rivulet's steps. Home = connected over a local address; away = remote or relay.
- **Decision** (`PlexQuality.kt`, unit-tested): original if its bitrate is under the cap and the head unit can decode it (H.264/HEVC/VP9/MPEG-2/MPEG-4 video; any audio, since FFmpeg covers AC3/DTS/TrueHD). Otherwise transcode to the step at or under the cap. Relay: always transcode at 1.5 Mbps or lower. AV1, 10-bit HEVC and 4K: transcode.
- **Direct play:** `{server}{Part.key}?X-Plex-Token=…` straight into the existing ExoPlayer pipeline. External subtitles: `/library/streams/{id}` as a subtitle track; subtitles inside the file come through ExoPlayer.
- **Transcode:** `/video/:/transcode/universal/start.m3u8` with a `session` id; stop it on stop or switch (`/video/:/transcode/universal/stop?session=`). Ask for stereo AAC (the car mixes 5.1 down anyway, and it saves data).
- **Fallbacks:** direct play that doesn't start within 10 s → retry once as a transcode. Repeated stalls → one step lower.
- **Gear:** video only in P, through the same gate code as USB. Leaving P with a transcode → restart at 0.72 Mbps for audio only after 10 s, as live TV does now; going back into P → back to the chosen quality.
- **Data use:** count Plex bytes under the current connection, as the stations are counted.

### Phase 4: progress sync

- `PlaybackService` gets a `PlexSession` beside the USB position code: `/:/timeline?ratingKey&key&state&time&duration` on start (`playing`), every 10 s, and on pause and stop. Past 90 %: stopped, then `PUT /:/scrobble`.
- Long-press ✓ → `/:/scrobble` or `/:/unscrobble`. Continue and progress bars come from Plex's `viewOffset` / `viewCount`, not local storage.
- **Next episode:** next child of the season, then the next season's first episode; queued as the next media item, as USB does.
- **Signal drop:** keep the last position locally and send it when the connection comes back, so a tunnel doesn't lose your place.

### Phase 5: Plex music (optional, but suits a car)

- `artist` libraries: Artists → Albums → Tracks, plus playlists. Audio only, so it plays **in any gear**. Use the Simple driving screen and the steering-wheel next/previous keys.
- Direct play; when away and the file is over 320 kbps, transcode with `/music/:/transcode/universal/start.m3u8`. Cover art goes to the media notification, as album art does now.

### Phase 6: Network check, docs, release

- Network check gets a Plex section: plex.tv reachable, each server address with its HTTP status and time, and which one was chosen.
- README: Plex section (sign-in, home vs away, Plex Pass needed for remote playback since April 2025, relay limits). Release notes.

## Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Remote playback needs Plex Pass (owner) or a Remote Watch Pass | Away from home, nothing plays | Detect the error and say so; home Wi-Fi still works |
| Router blocks plex.direct addresses | No local HTTPS | Cleartext fallback for the server's LAN IP (phase 0 decides) |
| Car's internet exits from an odd IP or blocks the server port | Remote fails, relay only | Relay at 1.5 Mbps; hotspot |
| Relay is slow (about 1–2 Mbps) | Low quality | Transcode at 1.5 Mbps or lower automatically |
| Data use away from home | 4 Mbps ≈ 1.8 GB/h | Away default 4 Mbps, audio-only bitrate outside P, data counter |
| `MainActivity` refactor breaks USB | Regression | Separate commit, USB checked before going on |

## Order

Phase 0 → 1 → 2 → 3 → 4 gives a usable v0.7.0 (Plex TV shows). Phase 5 (music) and phase 6 follow in v0.7.x.
