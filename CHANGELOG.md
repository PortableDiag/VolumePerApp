# Changelog

All notable changes to VolumePerApp.

## [0.1.0] — 2026-08-16

First working version. Per-app volume verified end to end on Android 15 / API 35
with the signed release build installed as a privileged system app.

### Added

- **Routing engine.** Registers an `AudioPolicy` carrying one `AudioMix` per
  target uid (`RULE_MATCH_UID`, `ROUTE_FLAG_LOOP_BACK`, 48 kHz stereo PCM 16).
  The platform diverts that uid's audio into the app, `StreamPump` scales it and
  renders it back out through an ordinary `AudioTrack`.
- **Magisk module** that installs the APK into `/system/priv-app/VolumePerApp`
  with a `privapp-permissions` XML granting `MODIFY_AUDIO_ROUTING`,
  `CAPTURE_AUDIO_OUTPUT` and `CAPTURE_MEDIA_OUTPUT`. `customize.sh` refuses to
  install rather than half-work: it checks the APK is present, the API level is
  29+, and warns when a `/data` copy would shadow the privileged one.
- **Mixer screen.** One fader per app, apps currently playing pinned to the top,
  mute, per-app boost with a clipping warning, and a fader ceiling setting.
  System stream faders below, which work with no permissions at all.
- **Mode banner** stating plainly whether the faders do anything — Privileged,
  Limited, or nothing adjusted yet. A mixer that silently does nothing is the
  failure worth designing against.
- **Diagnostics screen** that walks the engine's own call chain and names the
  step that failed, and reports live per-app peak in/out and measured gain.
- **Playback detection** from two sources, because neither is sufficient:
  `AudioManager.getActivePlaybackConfigurations()` (every player, but
  `getClientUid()` is hidden) and `MediaSessionManager.getActiveSessions()`
  (package names, but needs notification-listener access and misses games).
- **Ocean and Terminal themes**, both DayNight, switchable from the toolbar.
- **Adaptive launcher icon** — three faders at three heights — with a monochrome
  variant for themed icons.
- **`tools/probe/`** — pins the hidden audio-routing surface against a real
  device rather than against the docs. Output for API 35 in
  `docs/hidden-api-api35.txt`.
- **`tools/tone/`** — test fixture emitting a 440 Hz sine at exactly 0.5 full
  scale, so the applied gain can be measured instead of guessed.

### Fixed during bring-up

Both found by driving the real UI on the emulator rather than by writing
preferences behind the app's back — the second method hid them.

- **The mixer screen never refreshed once routing started.** `RoutingEngine` had
  a single state-listener slot and both `MixerService` and `MixerActivity`
  claimed it; the service registered last and silently displaced the UI. The
  symptom was a screen that routed audio correctly while still displaying
  "Nothing is adjusted" and omitting the *routed* badge. Now a listener list.
- **Playback detection was gated on the routing engine running,** so on first
  open — before anything is adjusted, which is by design when the engine is
  idle — the app could not show what was playing. That is backwards: the app you
  want to turn down is the one making noise right now. The watcher now runs
  whenever either the mixer is on screen or the engine is routing, reconciled in
  one place.
- **Faders dimmed whenever the engine was idle,** implying they were inert when
  they were merely unused. Dimming now means "a policy registration was actually
  refused", and the banner keeps the stronger claim: it says Privileged mode only
  once a policy has been accepted.

### Known limits

- Apps that set `android:allowAudioPlaybackCapture="false"` cannot be diverted;
  they are marked **blocks capture** in the mixer. See below for why the obvious
  fix is worse than the problem.
- A fader applies per uid, so apps sharing a `sharedUserId` share a fader.
- Boost above 100 % clips. Samples are clamped, so it distorts rather than
  wrapping into noise.

### Notes for the next version

- `allowPrivilegedPlaybackCapture(true)` is deliberately **not** used. It would
  capture apps that opted out, but the platform caps the privileged-capture path
  at `PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE` 16000, one channel, 2 bytes per
  sample. Requesting 48 kHz stereo alongside it throws
  `IllegalArgumentException: Privileged audio capture sample rate 48000 can not
  be over 16000kHz`. Accepting the cap would degrade every routed app to 16 kHz
  mono.
- `AudioManager.SUCCESS` / `ERROR_*` and
  `AudioPlaybackConfiguration.getPlayerState()` are hidden and absent from the
  public SDK, so they are pinned as constants read off the platform by the probe.
- Lint's `BlockedPrivateApi` family is disabled for this module with a written
  reason: the app declares `android:usesNonSdkApi="true"` and is installed as a
  system app, which is the platform's own documented allowance
  (`ApplicationInfo.isAllowedToUseHiddenApis`). The feature does not exist in the
  public SDK, so the rule can only be acknowledged, not satisfied.
