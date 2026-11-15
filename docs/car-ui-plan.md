# MG4 Streams: car UI and next-features plan

Written 2026-11-14, after the first car tests of v0.2.1. Screen: 1778 × 720 px at 160 dpi (1 dp = 1 px). Layout today: 88 dp top bar, 62 % now-playing panel, 38 % three-column grid.

## Status (v0.4.0, 2026-11-14)

Built from this plan: driving mode (2.1), parked extras (2.2: 640 × 360 video, group chips, guide progress, 3/6 columns), connection chip, Retry and error hints, night dim, swipe, tap feedback (2.3), data use counter, auto-resume (on by default, never TV) and recently played (section 3). Next round (built, not yet tested in the car): smaller release APK (R8, 14 MB to 2.7 MB), `scripts/release.sh`, Arrange presets, skipping dead stations, and album art. Not done yet: sleep timer, steering-wheel long-press, USB backup.

Launcher card and instrument cluster: there's no path for a third-party app. Both read only SAIC's own sources (radio, USB, Bluetooth, CarPlay/Android Auto bridge, and "online music", which is hard-wired to the Amazon Music app's service). Showing our station there would mean replacing the Amazon Music system app. Not done.

## 0. Done in v0.3.0

| Change | Why | Needs the car for |
|---|---|---|
| Updates download to **Downloads** (DownloadManager), then Downloads opens and you tap the APK | The head unit refuses installs the app starts itself ("not a system app"). Same method as MG4Control | That the Downloads screen opens and the install goes through |
| HLS variants re-ordered lowest bitrate first; on live TV, 10 s after video turns off (leaving P) the stream reloads once on the lowest variant | With video off, ExoPlayer kept loading the first-listed variant (Seven: 1.7 Mbps, others up to 4.5 Mbps) for audio only. Now about 0.5 Mbps: roughly 230 MB/h instead of 0.8–2 GB/h on the car's data, and fewer stalls | Audio gap when shifting out of P (expect 1–2 s, once) |
| **Network check** in Settings | Shows the connection type, public IP and country, and each step of the station on screen (redirects, playlist, variant, segment) with HTTP codes and speed | Run it on Seven over the car's internet |
| Playlist parsing off the UI thread, smaller read buffers, grid tiles reuse their views, logos don't blink on refresh | Smoother start and scrolling | Nothing |

## 1. Seven on the car's own internet

**What we know (from the code and files, not yet the car):**

- Seven's entries redirect (`i.mjh.nz/.r/seven-mel.m3u8`) to a Yospace ad-insertion session (`*.yospace.com`, served from AWS Sydney) with CloudFront-signed segments. Seven geo-checks the client address. The other networks are less strict.
- The car's own internet comes from the car modem (TBOX) over internal Ethernet. `arp_update.sh` drops **all IPv6 on `eth0*`**, so that link is IPv4-only. On Wi-Fi/hotspot the unit gets IPv6 as well.
- The usual reason a single broadcaster fails on a car or IoT SIM is that the SIM's internet exit is not an ordinary Australian mobile address (roaming IoT SIMs often exit overseas or from a data-centre range), and the broadcaster refuses it with HTTP 403.

**Step 1 (next drive):** play Seven on the car's internet, then open Settings > **Network check**. Photo the result. It tells us which of these it is:

| Network check shows | Meaning | Fix |
|---|---|---|
| Country not AU, or `yospace.com: HTTP 403` | Seven blocks the car's network | Hotspot for Seven |
| DNS fails or times out for `*.yospace.com` | Car network DNS problem | Use a fixed DNS-over-HTTPS lookup for stream hosts in the app |
| Variant/segment slow (under ~600 kbit/s) | Link too slow for Seven's lowest variant | v0.3.0's lowest-variant change may already fix it |
| Everything 200 but still won't play | Player-side issue | Send me the on-screen error text |

**Workaround:** use the phone hotspot (or Wi-Fi) for channels that block the car's internet. A home relay was built and then removed at the user's request (2026-11-14): not comfortable exposing a service from home.

## 2. Car UI plan

### Principles
- Everything you need while driving is reachable with **one tap, in the lower two-thirds** of the screen, at 88 dp or more.
- No text input, lists, or settings while moving (unchanged gate).
- Glanceable: one large line for the station, one for what's on.

### 2.1 Driving mode (auto when not in P)

The screen simplifies when the gear isn't P, which the app already knows:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ [ Radio ] [ TV ] [ ★ ]                                   ●  4G car  12:41    │
├──────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────┐   Triple J                                                       │
│  │  logo   │   Now: Artist – Song                                             │
│  └─────────┘                                                                  │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                       │
│  │ fav1 │ │ fav2 │ │ fav3 │ │ fav4 │ │ fav5 │ │ fav6 │     6 big presets      │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘     (180 dp)           │
│            [ ⏮ ]          ( ▶ / ■ )          [ ⏭ ]                            │
└──────────────────────────────────────────────────────────────────────────────┘
```

- Search box and Settings hidden (not just dimmed).
- The grid becomes **one row of six favourite presets**, 180 dp tiles, no scrolling. That's the main change: scrolling a grid at 100 km/h isn't safe.
- If there are no favourites, the six most-played stations fill the row.

### 2.2 Parked mode (P)

Today's layout, with:
- **Video panel larger**: 16:9 at 853 × 480 instead of 533 × 300, with the station text beside it.
- **Grid density toggle**: 3 columns (big) or 6 columns (compact, two-line names) for browsing 165 TV channels.
- **Group chips** above the grid for TV (Melbourne, Regional, Sport, News…, from `group-title`).
- **Guide strip** under the video for TV: now / next with a progress bar (data is already in `Epg.kt`).

### 2.3 Everywhere

| Item | Change |
|---|---|
| Status | A small connection chip top-right: `Wi-Fi`, `Car 4G`, `Offline`, `Reconnecting…` (amber), with the stream bitrate when parked |
| Errors | Plain-language banner plus a one-tap **Retry**; channel-specific hints ("Seven blocks the car's internet. Use a hotspot") |
| Night | Darker palette after sunset or when headlights are on is out of scope (that's a car read). Use a time-based dim between 19:00 and 06:00 instead |
| Text | Station name 42 sp → 48 sp in driving mode; now-playing 24 sp → 28 sp |
| Touch | Prev/next 88 → 110 dp in driving mode; swipe left/right on the logo to change station |
| Feedback | Tap highlight on every button (some are flat today) |

### 2.4 Order

| Phase | What | Car test |
|---|---|---|
| A | Driving mode: hide search/settings, preset row, larger controls | Reach and glance while driving (passenger tests) |
| B | Connection chip, Retry button, channel hints | Car internet vs hotspot |
| C | Parked: bigger video, group chips, guide strip, density toggle | Look on the real screen |
| D | Night dim, swipe gestures | Evening drive |

## 3. Other things worth adding

Ranked by value for the effort:

2. **Smaller APK**: release build with R8 shrinking. The debug APK is 14 MB; a release build is about 3–4 MB, so updates download 4× faster on the car's internet.
3. **Data use counter**: MB used today and this month, per network (car modem vs Wi-Fi), shown in Settings. Useful if the car's data plan has a cap.
4. **Resume after the car restarts**: auto-resume exists but is off by default. Turn it on by default for radio only (TV stays off).
5. **Recently played** as a fourth tab, or as the fallback for the preset row.
6. **Sleep/stop timer** for when you're parked and charging.
7. **Steering-wheel long-press** for favourites only (needs checking what key codes the wheel sends).
8. **Backup and restore** of favourites and playlist addresses to a USB stick (one text file).
9. **Release automation**: a script that bumps the version, builds, signs, and runs `gh release create`, so publishing an update is one command.

Not recommended: anything that writes to the car, hides the SAIC bars, or plays video outside P.
