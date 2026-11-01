# Streamyfin: Android performance fork

An unofficial fork of [Streamyfin](https://github.com/streamyfin/streamyfin), the Jellyfin client by [Fredrik Burmester](https://github.com/fredrikburmester) and contributors. It isn't affiliated with or supported by the Streamyfin project, so please don't report problems with this fork upstream. For the app itself and its documentation, see the [original project](https://github.com/streamyfin/streamyfin).

This branch is `v0.55.2` plus fixes for Android performance and subtitle problems, built for personal use. No builds are published, and these changes haven't been submitted upstream. The notes below record each issue so upstream reports can be written later.

Everything was tested on one device: a Redmi Note 11 Pro 5G (Snapdragon 695, 5.5 GB RAM, Android 13 / HyperOS, 120 Hz), on 2026-11-01. Upstream was searched that day, and `develop` was checked at `284d5527f` (2026-10-29). Results on other devices may differ.

Licensed under the [Mozilla Public License 2.0](LICENSE.txt), like the original.

## Results

Compared with official 0.55.2, on the same phone and server.

| Measurement | Official | This fork |
|---|---|---|
| JS thread when idle | 75–85% of a core | about 0% |
| Native heap when idle | 760–800 MB | about 145 MB |
| Total app memory (PSS) when idle | about 1.34 GB | 0.47–0.49 GB |
| Total app memory after browsing | about 1.27 GB | about 0.57 GB |
| Cold start, excluding the first after install | 2.5–3.8 s | about 1.6 s |
| CPU during playback, all threads (800% = all cores) | 151% | 95% |
| Tab switch, median tap to first change (6 taps) | 394 ms | 283 ms |
| Slowest of those tab switches | 1191 ms | 405 ms |
| Hero carousel, median frame | 27 ms | 13 ms |
| Hero carousel, janky frames | 78% | 6–7% |
| Library grid, median frame, `verify` to `speed` (same build) | 17 ms | 12 ms |
| Code in the APK (dex) | 85 MB | about 28 MB |

- The memory and log results depend heavily on one install's data: its log had grown to 26.8 MB (#2). A new install wouldn't start from that state, though the polling in #1 affects every install.
- Tab timings include about 250 ms from the test setup (screen recording), and were measured before #10–#12. A native Settings row measured 254 ms the same way.
- Janky-frame percentages vary widely between runs. Median frame time and missed vsyncs are more reliable.
- Compare builds in the same compile state (#12), since it changes the results a lot.

## Fixed

### 1. Root log query polls every second and rewrites the whole query cache

**Upstream:** no report or PR found. Still present on `develop`. Possibly a cause of the open [#379](https://github.com/streamyfin/streamyfin/issues/379) ("Extreme UI lag", Android), since the code dates from December 2024, a month before that report, and it describes the app slowing down over time. Not confirmed.

**Cause:** `LogProvider` wraps the whole app and runs `useQuery({ queryKey: ["logs"], refetchInterval: 1000 })`. Each second this parses the stored log. Each result updates the query cache, and `PersistQueryClientProvider` then serializes the entire cache to MMKV on the JS thread. Only Settings → Logs reads the data.

**Evidence:** at idle the JS thread uses 75–85% of a core on every screen, with zero frames drawn. A scheduler probe put about 60% of JS wall time in `persistClient` and in the `setInterval` from `onQueryUpdate`. Removing that one line took the JS thread to 1%.

**Fix:** `utils/log.tsx`, `app/_layout.tsx`. `useLog` now owns the query, so it only polls while the Logs screen is mounted, with `gcTime: 0`. `LogProvider` is removed. The Logs screen still updates live (JS about 6% while open, 1% after leaving).

### 2. The stored log has no size limit

**Upstream:** no report found. `develop` still limits entries by count only.

**Cause:** `MAX_LOG_ENTRIES` limits the number of entries, not their size, and `storeLogEntry` parses and rewrites the whole log for every new entry. A log that rarely reaches 250 entries can keep old, oversized entries for months.

**Evidence:** the test install's log held 115 entries totalling 26.8 MB, the oldest three months old. 17 `Jellyseerr response error` entries from an older build accounted for essentially all of it, at up to 2 MB each. Current code truncates Seerr bodies (`hooks/useSeerr.ts`), so this is about stale data and the missing general limit. How often it happens to other users is unknown.

**Fix:** `constants/Logs.ts`, `utils/logStorage.ts`, `utils/migrations.ts`. Entries are capped at 8 KB per field, and migration 5 shrinks existing logs. Builds with the #1 fix alone used about 730 MB at idle, against about 580 MB once this and #3 were in. Cold start reached about 1.7 s only with these too, though it wasn't measured separately for each fix.

### 3. The log is part of the persisted query cache

**Upstream:** no report found.

**Cause:** the log has its own MMKV key but was also dehydrated as the `["logs"]` query, so it was stored twice and written with every cache save.

**Evidence:** `["logs"]` was 26.79 MB of a 27.97 MB persisted cache across 106 queries. `JSON.stringify` alone took 190–490 ms per save, and persist work reached about 700 ms per 5 s window on the JS thread during navigation. Without the log, the cache is about 1.2 MB.

**Fix:** `utils/queryPersistence.ts` excludes the key by name, because a restored entry carries the default `gcTime`.

### 4. "System" subtitle font resolves to an arbitrary system font

**Upstream:** no report of this symptom found. The mapping comes from merged [#2019](https://github.com/streamyfin/streamyfin/pull/2019) and is still on `develop`. [#2137](https://github.com/streamyfin/streamyfin/issues/2137) (broken Vietnamese subtitles on Android, closed) might be related; not tested.

**Cause:** "System" maps to `sub-font=sans-serif`. libass runs without a fontconfig config on Android ("No usable fontconfig configuration file found, using fallback"), so nothing aliases `sans-serif` to a real family.

**Evidence:** the mpv log shows `fontselect: (sans-serif, 400, 0) -> /system/fonts/MiClockTibetan-Thin.ttf`. Subtitles rendered squashed with no visible spaces ("Erwirdsichervielesrichtigmalen").

**Fix:** `MPVLayerRenderer.kt` maps "System" to `Roboto`, confirmed correct by screenshots. Only Latin text was tested, so check other scripts before proposing this upstream, and consider devices without Roboto.

### 5. Hero carousel redraws a live blur and decodes backdrops at full size

**Upstream:** no report found. Both are still on `develop`. The carousel came from merged [#1983](https://github.com/streamyfin/streamyfin/pull/1983).

**Cause:** the Android glass panel draws a second copy of the card art through `Modifier.blur(24.dp)`, a shader on an offscreen layer. `HeroImageLoader` also decodes every image at server size with no downsampling.

**Evidence:** carousel median frame went from 27 ms to 13–15 ms, and janky frames from 78% to 7–23%, before #12 improved them further. The two changes were tested together, so their individual effects are unknown.

**Fix:** `HeroCarouselContent.kt`, `HeroImageLoader.kt`. Images decode at no more than twice the screen width. The panel draws a 64 px copy scaled up, prepared once. It looks slightly blockier than a true blur, but reads as frosted glass, and now also works below Android 12.

### 6. Unpinned Cast framework breaks clean builds

**Upstream:** already fixed by merged [#2295](https://github.com/streamyfin/streamyfin/pull/2295), newer than v0.55.2. No new issue needed.

**Cause:** the Cast plugin defaults to version `+`, which now pulls `mediarouter:1.9.0-beta01`, needing AGP 9.1 and compileSdk 37.

**Fix:** `app.config.ts` pins 22.3.1, the same as #2295 and the official APK.

### 10. The MMKV file never shrinks after large values are gone

**Upstream:** no report found. No `trim` on `develop`.

**Cause:** MMKV keeps its whole file mapped in memory and doesn't shrink by itself. Rewriting a 28 MB value every second had grown it.

**Evidence:** `Other mmap` in `dumpsys meminfo` went from about 205 MB to about 4 MB after one `trim()`, and total memory from 746 MB to about 520 MB. Login and settings survived.

**Fix:** `utils/migrations.ts`, migration 6, once. Only helps installs that were hit by #1 and #2. Whether the file grows back over months wasn't tested.

### 11. Release builds aren't shrunk with R8

**Upstream:** open [#2368](https://github.com/streamyfin/streamyfin/pull/2368) does this, ahead of Expo SDK 58 enabling it by default. No new issue needed.

**Evidence:** dex went from 85 MB to about 28 MB, and idle memory fell by about 70–80 MB. Scrolling and start-up were unchanged.

**Fix:** `app.json`. Uses `-keepnames` for exceptions from #2368, plus broad keep rules for the native-facing modules. Those cover the `RNHeadlessAppLoader` breakage #2368 reports, but they're less precise than its rules, so upstream's rules should be preferred once merged.

**Checked:** home, carousel filter, Cast picker (not connected), downloads screen, search, favorites, library, settings, logs, item page, playback with hardware decode, seek, player options.

**Not checked:** an actual download, connecting to a Cast device, music, the ExoPlayer engine, background tasks, notifications. If one of those breaks, suspect this first.

### 12. Sideloaded installs run uncompiled until the system gets to them

**Upstream:** no report found. This is Android behaviour rather than an app bug, but it affects anyone installing an APK directly, and it may explain some "laggy on Android" reports. Not confirmed.

**Cause:** `adb install` leaves the app at compile status `verify`, running on the interpreter and JIT until background dexopt, which waits for the phone to be idle and charging. Store installs can get cloud profiles.

**Evidence, same build:**

| State | Cold start | Grid median / jank | Home median | Idle memory |
|---|---|---|---|---|
| `verify` | 1.7–1.9 s | 17 ms / 56–68% | 12–17 ms | 594 MB |
| `speed-profile` | 1.45 s | 12 ms / 14–21% | 15–16 ms | 633 MB |
| `speed` | 1.6–1.7 s | 11–12 ms / 2–26% | 13 ms | 707 MB |

Measured before #10 and #11, in one run per state, so the memory figures are only comparable with each other.

**Fix:** none in the app. After every install, run `adb shell cmd package compile -m speed -f com.fredrikburmester.streamyfin`. Whether the system later downgrades `speed` wasn't tested. Check with `adb shell dumpsys package dexopt`.

## Not fixed

### 7. Scrolling misses the 120 Hz budget

**Upstream:** old closed [#91](https://github.com/streamyfin/streamyfin/issues/91) (library scrolling). Open [#379](https://github.com/streamyfin/streamyfin/issues/379) may overlap. Merged [#2296](https://github.com/streamyfin/streamyfin/pull/2296) and open [#2378](https://github.com/streamyfin/streamyfin/pull/2378) are newer image and card work, neither tested here.

**Evidence:** with `speed` compilation (#12), the library grid's median frame is 11–12 ms and often close to smooth, against an 8.3 ms budget. Home is 13–16 ms and varies a lot between runs, about the same as official (14 ms, 38% janky in one uncompiled run), so these fixes didn't clearly improve it. Native Settings measures 5 ms with 0.4% jank, though it's a far simpler screen. Roughly 100 frames per second are still delivered, so this is mild rather than severe.

The render thread dominates, and time is spread across general Skia drawing with no single hot spot (`GrDrawingManager::flush` about 30%, `eglSwapBuffers` about 17%).

**Ruled out:**
- Rounding card images instead of `overflow: hidden`: no difference in an A/B/A/B test.
- The JS changes: official JS on the same native build scrolls the same in an A/B/A/B test.
- Heat: battery about 25 °C, thermal status 0, full clocks.

### 8. The main thread wakes on every frame while idle

**Upstream:** no report found.

**Evidence:** about 123 wake-ups per second with nothing drawn, using 20–35% of one core (about 3–4% of total CPU), and stopping in the background. Most is React Native's own `FabricUIManager$DispatchUIFrameCallback`, which re-schedules itself every frame while resumed, so it's upstream RN behaviour. About a sixth is `worklets` `AnimationFrameQueue`, which is unidentified: a probe on the UI runtime's `requestAnimationFrame` recorded no calls.

### 9. Push token fails on every launch

**Upstream:** no report of `FIS_AUTH_ERROR` found.

**Evidence:** on builds re-signed with a non-official key, `getExpoPushTokenAsync` fails with `FIS_AUTH_ERROR`. It was logged three times per launch, because it runs again on every session change.

**Partial fix:** `app/_layout.tsx` asks once per launch. The failure itself remains.

**Likely cause, unconfirmed:** the bundled Firebase config rejects re-signed builds. If so, push notifications can't work with a non-official signing key without a separate Firebase project. Whether official-signed builds fail too wasn't tested.

### 13. App version shows `#[object Object]` on local builds

**Upstream:** not searched. Cosmetic. Settings shows `0.55.2 · <commit> · #[object Object]`, so `extra.build.runNumber` isn't a string on these builds. Cause not investigated, and not checked on non-R8 builds.

## Checked, not bugs

- **Software video decoding:** one playback used `av:h264` threads while screen recording was running. Without recording, hardware decoding was used (`MediaCodec_loop`). Probably caused by the test. Open [#1853](https://github.com/streamyfin/streamyfin/issues/1853) reports hardware decode failing on another device, but no link was established.
- **Possibly related, not investigated:** open [#1302](https://github.com/streamyfin/streamyfin/issues/1302) and [#1089](https://github.com/streamyfin/streamyfin/issues/1089) report lag after downloading media. No downloads were tested here.

## Building

You need your own signing key. Android won't install this over an official Streamyfin APK signed with a different key unless you uninstall first, which deletes the app's data. The package name is unchanged, so it can't sit alongside an official install. `versionCode` is 161, so it can replace an official 0.55.2 (160) signed with the same key.

```powershell
bun install --frozen-lockfile --ignore-scripts
$env:JAVA_HOME = "<JDK 17>"; $env:EXPO_TV = "0"; $env:NODE_ENV = "production"; $env:SENTRY_DISABLE_AUTO_UPLOAD = "true"
node_modules\.bin\expo.exe prebuild --platform android --clean --no-install
cd android; .\gradlew.bat assembleRelease -PreactNativeArchitectures=arm64-v8a
apksigner sign --ks <keystore> --out <out.apk> app\build\outputs\apk\release\app-release.apk
adb install -r <out.apk>
adb shell cmd package compile -m speed -f com.fredrikburmester.streamyfin
```

The compile step matters (#12). Skip it and scrolling is noticeably worse until the system compiles the app.

When moving to a newer upstream version, raise `versionCode` in `app.json` above the official release's, and drop the Cast pin if the base already includes #2295.

Tests run: `jest utils/logStorage.test.ts utils/log.test.ts utils/migrations utils/queryPersistence.test.ts utils/atoms/settings.test.ts` (all pass on their last runs). The full test suite and the Kotlin unit tests were not run; `SubtitleFontTest` was updated but not executed.

GitHub Actions is disabled on this fork, so upstream's workflows don't run here.
