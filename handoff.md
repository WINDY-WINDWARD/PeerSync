# PeerSync Session Handoff (2026-07-24)

## Session Goal

Stabilize PeerSync real-time audio across devices, with special focus on:

1. Voice reliability between peers.
2. Mixed music + microphone behavior similar to a single shared stream.
3. Audio output routing correctness (speaker, earpiece, Bluetooth).
4. Host-only music control ownership.
5. Host-adjustable music playback level.
6. Final code commit + push with a detailed engineering handoff.

---

## User-Reported Problems During Session

1. Audio intermittently failed after mixed-stream refactors.
2. Playback got stuck on earpiece speaker path on both devices.
3. Music was too quiet for host playback/mix.
4. Non-host request-host flow was no longer desired.

---

## High-Level Work Completed

### A) Session/control-plane and UI ownership updates

- Removed/rewired stale media-host request UI callback path from Compose wiring.
- Kept host-only playback control behavior in engine/UI path.
- Updated nav and app callback signatures to remove now-obsolete `onRequestMediaHost` dependency from UI flow.

### B) Native audio architecture refactor integration (already in this working set)

- Continued using C++ side mixed path where local music is fed into native ring buffers and mixed into:
  - outgoing mic/voice stream path
  - local output render path
- Maintained peer volume gain controls in native mixer path.
- JNI bridge includes local music feed, free-space query, local music gain, peer gain, and stream error callback plumbing.

### C) Routing bug investigation and fix pass (main issue)

#### What logs showed

- Engine-level counters confirmed packets/frames flowing (`PeerSyncEngine: AUDIO FLOW ...`).
- Logcat around stream restarts/re-election showed route API calls occurring, but policy churn could still override route.
- Auto-route logic previously treated wired output as `EARPIECE`, which could force non-speaker behavior even when user expected loudspeaker.
- Route could also drift during stream restarts, requiring route re-assertion after audio start.

#### Routing fixes implemented

In `composeApp/src/androidMain/kotlin/com/peersync/app/audio/AudioBridge.kt`:

1. Added explicit route state tracking (`currentRoute`).
2. Split route application into a dedicated `applyAudioRoute(route, source)` method with detailed diagnostics.
3. Improved API 31+ communication-device selection by selecting from an ordered list of target device types per route.
4. Added explicit fallback behavior and success logging (`target`, `setSuccess`, active communication device).
5. Adjusted automatic route policy:
   - Bluetooth present -> prefer Bluetooth.
   - Otherwise -> default to loudspeaker (not earpiece) to avoid earpiece lock behavior.
   - Only fallback from Bluetooth when Bluetooth disappears.
6. Added route re-assertion after successful stream start, including a delayed re-apply (300ms) to survive OEM routing policy races.
7. Kept legacy pre-S handling (speakerphone/SCO toggles) with extra safety wrappers.
8. Added output device inventory logging to support future field debugging.

### D) Host music loudness improvements

1. Increased native default local music gain from `0.3f` to `1.0f` in:
   - `composeApp/src/androidMain/cpp/audio_engine.h`
2. Expanded host UI music volume slider range from default 0..1 behavior to a boost range:
   - Slider now spans `0f..3f` with stepped control.
   - Initial UI slider value set to `1.0f`.
   - File: `composeApp/src/commonMain/kotlin/com/peersync/app/ui/activesession/ActiveSessionScreen.kt`
3. Clamped engine-side setter to safe range:
   - `volume.coerceIn(0f, 3f)`
   - File: `composeApp/src/androidMain/kotlin/com/peersync/app/engine/PeerSyncEngine.kt`

---

## Files Touched in This Working Set

1. `composeApp/src/androidMain/cpp/audio_engine.cpp`
2. `composeApp/src/androidMain/cpp/audio_engine.h`
3. `composeApp/src/androidMain/cpp/jni_bridge.cpp`
4. `composeApp/src/androidMain/kotlin/com/peersync/app/MainActivity.kt`
5. `composeApp/src/androidMain/kotlin/com/peersync/app/audio/AudioBridge.kt`
6. `composeApp/src/androidMain/kotlin/com/peersync/app/audio/MediaHostManager.kt`
7. `composeApp/src/androidMain/kotlin/com/peersync/app/engine/PeerSyncEngine.kt`
8. `composeApp/src/androidMain/kotlin/com/peersync/app/network/TcpControlPlane.kt`
9. `composeApp/src/commonMain/kotlin/com/peersync/app/App.kt`
10. `composeApp/src/commonMain/kotlin/com/peersync/app/navigation/NavGraph.kt`
11. `composeApp/src/commonMain/kotlin/com/peersync/app/ui/activesession/ActiveSessionScreen.kt`
12. `handoff.md`

---

## Build and Verification Status

### Compile status

- `./gradlew.bat assembleDebug` succeeded after signature/wiring fixes and routing updates.

### Connected-device availability during final pass

- `adb devices` showed one connected test device (`ZD222F4LJ4`) at the end of this pass.

### Runtime/log observations

- Audio flow counters remained active in logs, indicating frame and packet activity.
- Added richer routing logs in `AudioBridge` for direct verification of selected communication device and route source.

---

## Known Remaining Risk / Validation Needed

Because final end-to-end validation on two simultaneously connected devices was not completed in this terminal pass, the new route strategy should be validated on both phones with the following matrix:

1. No headset connected -> route toggle speaker/earpiece behavior.
2. Wired headset connected/disconnected during active session.
3. Bluetooth headset connected/disconnected during active session.
4. GO re-election or reconnect path -> route should persist or recover correctly.
5. Host music slider @ 100%, 150%, 200%, 300% for clarity/distortion balance.

The code changes target the reported earpiece lock and low music level directly, but practical device validation is still recommended after install.

---

## Notable Non-Blocking Warnings

1. Repeated NDK deprecation warning (`CXX5106`) regarding `ndk.dir` usage in `local.properties` and `android.ndkVersion` recommendation.
2. Several Android API deprecation warnings (e.g., SCO/speakerphone calls on older APIs), currently tolerated for compatibility.

---

## Suggested Next Engineering Actions

1. Install latest debug APK on both target devices and run the validation matrix above.
2. If any route still misbehaves on a specific OEM, gate an OEM fallback that forces speaker route post-start and post-device-change.
3. If boosted music causes clipping at high values, add limiter/compressor or soft clipper in native mix stage.
4. Optionally migrate all legacy route controls to communication-device-first strategy per API level and reduce deprecated path usage.

---

## Repository State at Handoff

- Branch: `master`
- Remote: `origin` (`https://github.com/WINDY-WINDWARD/PeerSync.git`)
- Session changes are committed and pushed in this handoff step.
