# Verification — 2026-10-28 (0.11.0)

Route Chrono 0.11.0 checks of routes with up to five ends, on a Motorola edge 30 ultra, Android 15/API 35:

- Docker JVM tests: 41 passed. The 5 new tests cover a five-end route: a Start trip finishing at each end, a trip from each end back to Start, an end-to-end hop that is interrupted and then a return from the reached end, per-end cooldown, and validation, matching and the reference distance. Debug and instrumentation APK builds passed. Lint: 0 errors, 19 existing warnings.
- Updated 0.10.0 in place. Full phone instrumentation: `OK (35 tests)`, 11.6 s. This covers the 3→4 migration from the exported schema, a five-end route persisting and a taught trip matching its fifth end, Results grouping for five ends, and the editor adding up to five ends, removing the last end and rejecting bad spacing.
- The production database migrated from schema 3 to 4. Every row in every table was unchanged and the new columns were empty: 6 endpoint pair rows, 8 journeys, 5,553 track points and 609 legacy fixes. The first test run pressed a real Save, which turned on automatic recording in the app preferences. That preference file was restored byte-identical from the pre-test snapshot, and the test no longer saves. All preference files ended identical.
- The editor showed five end buttons and **Remove last end**; the draft was cancelled. The phone was left on Record, paused, with no tracking service.
- APK SHA-256: `b168003f1afb5468e43b1c9534a16bb72910bb2f0999a3fc0c1414958e7000d6`.

**Not tested on 0.11.0:** saving a multi-end route through the Save button on the phone (covered by isolated tests), real driving with a multi-end route, other devices or Android versions. Private evidence is kept in the ignored `captures/five-ends/` folder.

## Previous verification — 0.10.0

Route Chrono 0.10.0 trip-abandon checks on Motorola edge 30 ultra, Android 15/API 35:

- Docker JVM tests: 36 passed (5 new: abandon only beyond 1.5×, longest-end and end-to-start references, only the crossing pair abandoned, restart from an endpoint, no trip when departure is already beyond). Debug and instrumentation APK builds passed; lint: 0 errors, 19 existing warnings.
- Updated 0.9.0 in place. Full phone instrumentation: `OK (30 tests)`, 10.7 s. An isolated database confirmed the abandoned trip left no journey or points while another pair's ongoing trip and an older recording remained. The silent, auto-cancelling abandon notification was posted separately from the tracking notification and removed after the test.
- Every production database row and preference file was unchanged: 6 endpoint pair rows, 7 journeys, 4,326 track points and 609 legacy fixes. No tracking service was left running.
- APK SHA-256: `c874aabfdc776ba19defcbf0202dce454f14a3c347142dfd9d26aca69e42561a`.

**Not tested on 0.10.0:** a real drive that crosses the limit, other devices or Android versions. Private evidence remains in ignored `captures/abandon/`.

## Previous verification — 0.9.0

Route Chrono 0.9.0 start-and-two-ends checks on Motorola edge 30 ultra, Android 15/API 35:

- Docker JVM tests: 31 passed (7 new for two-end routes: first end reached, either end back to start, end-to-end hop interrupted with a new trip from that end, validation and taught-trip matching). Debug and instrumentation APK builds passed; lint: 0 errors, 19 existing warnings.
- Updated the installed 0.8.0 app in place. Full phone instrumentation: `OK (28 tests)`, 16.2 s, including 2→3 migration from the exported schema, persisted reached ends, Results grouping into five direction headings and the editor's set/clear/spacing checks.
- The production database migrated from schema 2 to 3; every row in every table was unchanged and new columns were empty: 5 endpoint pair rows, 7 journeys, 4,326 track points and 609 legacy fixes. Preference files were identical.
- The editor showed three draft markers, the second-end hint and Clear end 2; the draft was cancelled without saving. Phone left on Record, paused, with no tracking service.
- APK SHA-256: `7e01b068508cabfe8bb2695fc4a65a8c46645fdb6041d74ec19d3cc16654f3da`.

**Not tested on 0.9.0:** real driving with a two-end pair, saving a two-end pair on the production database (covered by isolated tests), other devices or Android versions. Private evidence remains in ignored `captures/three-point/`.

## Previous verification — 0.8.0

Route Chrono 0.8.0 location-marker checks on Motorola edge 30 ultra, Android 15/API 35:

- Docker JVM tests: 24 passed. Debug and instrumentation APK builds passed; lint: 0 errors, 19 existing warnings.
- Full phone instrumentation: `OK (24 tests)`, 9.38 s. Three marker tests verify one centered dot across updates/redraws/recreation, unchanged endpoint framing/drafts, and rejection of missing, invalid, failed, cancelled, superseded or closing-activity results.
- Real location lookup showed a centered blue dot with a white ring on Record and in pair editing. Repeated requests retained one dot. Tapping it displayed the fix time without setting an endpoint; tapping elsewhere still set a draft endpoint, which was cancelled.
- Rotation retained the dot alongside distinct endpoint pins. Restored locked portrait and left Record paused with no tracking service.
- Every database row and original preference file remained unchanged: 4 endpoint pair rows, 2 journeys, 1,535 track points and 609 legacy fixes.
- APK SHA-256: `e5425f0d13d71b8155d412747ec52bb63288c3573ccf450e55b87d01ea14596f`.

**Not tested on 0.8.0:** other devices/Android versions, new driving, continuous tracking or physical GPS-loss scenarios. Error and cancellation paths use controlled callbacks. Private evidence remains in ignored `captures/location-marker/`.

## Previous verification — 0.7.0

Route Chrono 0.7.0 dark-theme checks on Motorola edge 30 ultra, Android 15/API 35:

- Docker core/app JVM tests: 24 passed. Debug and instrumentation APK builds passed; lint: 0 errors, 19 existing warnings.
- Final phone instrumentation: `OK (21 tests)`, 7.975 s. Added checks cover the large accessible toggle, missing-pair guard, live-state glow, GPS waiting and clearing the glow after stopping.
- Inspected Record, Pairs, rename dialog, Results and route detail. Dark map labels, mint route lines and endpoint markers remain visible. Map panning still works within the scrollable page.
- Checked 150% text in portrait and landscape: action buttons stack and scroll into view; tabs and Exit remain accessible. Side navigation-bar insets are respected. Restored the original font size and locked portrait orientation.
- Actual automatic toggle enabled monitoring with green glow, then paused; Exit stopped the service. Stronger recording glow was checked with simulated state, without creating a recording.
- Every database row remained unchanged: 4 endpoint pair rows, 2 journeys, 1,535 points and 609 legacy fixes. The only original preference change was the expected Exit generation increment from 15 to 16. Phone left on Record, paused, with no service.
- APK SHA-256: `981da19bff6266e683d4820b0c01f1930ffda908dc80f263b3ee2afeeac037eb`.

**Not tested on 0.7.0:** new driving scenarios, measured battery usage, other devices or Android versions. Private screenshots and data comparisons remain in ignored `captures/dark-theme/`. Optional state screenshots can be captured with instrumentation argument `-e captureAppearance true`.

## Previous verification — 0.6.0

Route Chrono 0.6.0 branding checks on Motorola edge 30 ultra, Android 15/API 35:

- Updated the installed 0.5.0 app in place, retaining the same application ID and signing key.
- Docker JVM tests (21), debug and instrumentation APK builds, and lint passed: 0 errors, 20 existing warnings.
- Phone instrumentation: `OK (19 tests)`, 5.158 s, including the Route Chrono notification title assertion.
- Visually checked the app heading and launcher icon. The launcher grid shortens the label to “Route Chr…”. Circular, rounded-square and monochrome icon previews were inspected locally.
- Every database row and original preference file remained unchanged, including 4 endpoint pair rows, 6 recordings, 1,569 track points and 609 legacy fixes.
- Phone left on Record with tracking paused and no active service.
- APK SHA-256: `e698e1acb77f956cceca264246549d1b652dcdf0be76962635ebb722b2fc66b3`.

**Not tested on 0.6.0:** new driving scenarios, live notification-shade presentation, changing the phone's icon theme, or other devices/Android versions. Notification wording was checked through device instrumentation without starting tracking. Private evidence remains in ignored `captures/branding-check/`.

## Previous verification — 0.5.0

App 0.5.0 was built with the existing Docker image and installed as an update on a Motorola edge 30 ultra running Android 15/API 35.

- `./scripts/build.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: passed. 21 JVM tests; lint has 0 errors and 20 dependency, API and style warnings.
- `adb shell am instrument -w dev.mmp.go.test/androidx.test.runner.AndroidJUnitRunner`: `OK (19 tests)`, 4.864 s. Isolated databases cover migration, teaching, alternate outward/return routes with endpoint dwell, reopening and interrupted evidence. Preferences tests cover Exit and cooldown restoration, expiry and boot mismatch.
- Synthetic teaching points on the phone's actual MapLibre map changed zoom from 16 to 15 at the viewport edge, stayed at 15 on redraw, then changed to 14 at the next edge. Start/current positions stayed visible.
- A simulated single automatic journey retained both saved endpoint markers in view. Two simultaneous pair journeys did not lock the map onto one pair. Fixtures were held in memory and did not add saved pairs or journeys.
- Record/Pairs/Results expose the selected tab to accessibility. All three pages were visually inspected: selected text, background and underline are clear, and Exit remains visible.
- Every pre-update row in every database table is unchanged, including 4 pair rows (archived included), 8 journeys, 1,649 track points and 609 legacy fixes.
- After testing, tracking remains paused (`automatic=false`, `exited=true`) with no active tracking service. The phone is left open on Record.

Deletion checks on 0.5.0:

- The Results detail action and confirmation were visually inspected; cancelling against an existing recording left it intact.
- Isolated storage tests deleted only the selected recording and its points, retained its pair and other/legacy records, and verified persistence after reopening. Completed, interrupted and no-GPS records are supported; repeated deletion is harmless and active recordings are refused.
- An injected database failure after point deletion rolled the transaction back, retaining both the recording and its points.
- UI tests confirmed Cancel, successful deletion with refreshed timing summary, last-record deletion in Other recordings, removal of an empty archived-pair group, and preservation of a saved pair with an empty recording list.
- All production database rows and original preference files were unchanged after testing. No real user recording was deleted; confirmed deletions used isolated databases through the production UI.

Final app APK SHA-256:

```text
32413fa5917923a45b16663f359a334d8cfbfa794e1d491e73cbfbe337b270c5
```

Private database copies and screenshots remain only in ignored `captures/`.

**Not tested on 0.5.0:** real driving, measured battery usage, physical reboot/power loss, forced GPS outage, other Android versions or phone models. Route inputs and map movements were synthetic; cooldown boot handling used controlled boot IDs. For the user's road check, wait at least 60 seconds after arrival before returning, then confirm the two separate results as described in [phone testing](phone-testing.md).

## Earlier device evidence

Baseline journey checks on 0.2.0 (their recorded data is retained by the 0.3.0 update):

- Actual installed database migration preserved every legacy row exactly: 4 configurations, 6 sessions, 609 fixes, 59 events, 6 runs and 6 observations.
- Map UI: created two distinct endpoint pairs; both were available for monitoring and result browsing. Temporary pairs were removed afterward and no empty removed groups remain in Results.
- Real stationary manual recording remained active for 207960 ms, saved 29 accepted GPS fixes, and stopped only on Stop. Its route map, 3:27 elapsed time, distance, average speed and summary were inspected.
- Screen-off capture: the phone reported `mWakefulness=Dozing` before and after a 20-second interval; saved fixes increased from 22 to 25. Foreground recording continued.
- The 0.2.0 APK update retained saved pairs and recording. Automatic recording started the foreground service; pausing left `ACTIVITY MANAGER SERVICES: (nothing)`. Force-stop and cold reopen took 482 ms and preserved the paused preference and recording.
- APK signature and 16 KiB ZIP alignment checks passed. Build/install shell scripts pass `bash -n`.

Exit and notification checks on 0.3.0:

- Exit is visible on all three tabs. Idle, manual capture and automatic monitoring shutdown left no active service. Reopening stayed paused; repeated Start/Exit did not re-enable monitoring.
- Background notifications visibly distinguished recording from monitoring. Notification Exit retained 4 GPS points in an interrupted 30488 ms journey, without learning a pair. Swiping away the monitoring notification also stopped tracking.
- Blocking the notification channel during capture stopped tracking and preserved its point. Starting with the channel blocked or runtime notification permission denied created no service and showed the explanation. Settings opened the Journey recording channel.
- Notification permission, flags and channel were restored; the temporary map pair was removed. Every pre-update database row was compared and remained unchanged. Three interrupted stationary test captures remain available.
- Final Exit removed the app from Recents. Reopening left no active service or current app location registration.
