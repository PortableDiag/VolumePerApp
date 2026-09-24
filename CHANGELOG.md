# Changelog

All notable changes to VolumePerApp.

## [0.3.1] — 2026-09-24

### Changed

- **Detents at every 10 % from 30 % up, not only at 100 %.** Releasing within
  4 % of 30, 40, 50 … lands exactly on it, so aiming for 30 no longer gives
  28 or 34. Still release-only, so dragging is never sticky, and none below
  30 %. A drag that starts on a detent and ends near that same detent is taken
  as deliberate and left alone, so 32 is still reachable: set 30, then nudge.
  The pull at 100 % widens from 2 % to 4 % to match. UI only; no engine change.

### Fixed

- **Playing or skipping a track in a routed app restarted audio and video in
  other apps.** Reported from real use: with one app faded and a video playing
  elsewhere, starting a track made the video loop, and nothing but reloading the
  page cleared it; skipping tracks did it again.

  The cause was this app's own design. A pump — the loopback `AudioRecord` and
  the `AudioTrack` that re-renders it — was created when a routed app started
  playing and destroyed when it stopped, on the reasoning that a thread should
  not exist while there is no sound to carry. But **opening and closing a
  `REMOTE_SUBMIX` capture makes the platform re-evaluate routing, and that
  invalidates the output tracks of unrelated apps**, which they handle by
  rebuilding their player. A track skip did it twice.

  Read off the device rather than reasoned from the docs: `dumpsys audio` showed
  bursts of new `REMOTE_SUBMIX` record sessions from this app — five in 28
  seconds during ordinary listening — and, in `PlaybackActivityMonitor`, another
  app's four media players carrying five generations of port ids against
  unchanged `piid`s. Same players, repeatedly re-routed.

  **A pump now lasts as long as the routing rather than as long as the sound.**
  Both ends are opened once when an app becomes routed; when it falls silent for
  two seconds the output track is *parked* (`pause` + `flush`) instead of being
  released, and unparks on the next non-silent buffer. Two seconds because the
  gap between two tracks is far shorter, so a skip no longer touches either
  endpoint. The cost is an idle reader per adjusted app.

- **A pump orphaned by a policy rebuild kept a dead record sink.** `syncRouting`
  claimed every pump was rebuilt against the new policy; it was not — the
  reconcile only dropped pumps whose app had stopped playing, so after a rebuild
  an app could be routed, look healthy, and be silent. Sinks now carry the
  policy generation they were made from and are rebuilt when it moves.

- **The output's `AudioAttributes` did not do what its comment claimed.** It set
  `FLAG_LOW_LATENCY` and said that was what stopped our own re-rendered output
  being captured by one of our own mixes. It is not, and never was. The
  attributes now set `ALLOW_CAPTURE_BY_NONE`, which is; the low-latency request
  was already on the track itself as `PERFORMANCE_MODE_LOW_LATENCY`.

### Not yet confirmed on hardware

The diagnosis is from the phone's own logs. The fix builds and is installed on
the test device, but the failure has not yet been reproduced against it.

**There is no release for this.** The version is `0.3.1-test1` / code 6, which
exists only to keep the build on the phone distinguishable from the published
0.3.0 — same `versionName` on two different engines is how you end up unable to
say which one you are looking at, and how a reinstall silently puts the old one
back. `magisk/module.prop` is deliberately left at v0.3.0: no module has been
packaged, and `tools/build-magisk.sh` derives it from `app/build.gradle` at
package time anyway.

## [0.3.0] — 2026-08-25

### Added

- **Suppress the system volume warning** — toolbar → *Suppress volume warning*.

  Android does two annoying things to a headset: it shows a warning when you
  raise past the "safe" level, and it lowers the volume on you after a long
  listen. **These are one mechanism, not two**, so this is one setting and not
  the two that were asked for. Both are gated on
  `SoundDoseHelper.mSafeMediaVolumeState`: the dialog is what the ACTIVE state
  does when you raise, and the lowering is what it does when the 20-hour
  `mMusicActiveMs` timer re-arms it. Nothing suppresses one and keeps the other.

  **It takes effect at the next restart, and the app says so** rather than
  pretending the switch did something now. The platform decides this in
  `SoundDoseHelper.updateSafeMediaVolume_l`, which runs only from
  `onConfigureSafeMedia` — whose two callers are `AudioService.onSystemReady`
  (forced) and `onConfigurationChanged` (not forced, so it re-runs on an MCC
  change alone). `audio.safemedia.bypass` is therefore read once per boot,
  before zygote.

  So the app does not apply it. It writes a flag to **device-protected
  storage** and the module's new `post-fs-data.sh` reads it and sets the
  property before `system_server` starts. Device-protected rather than the
  ordinary preferences because `post-fs-data` runs before the user is unlocked:
  measured on the device at that moment, `/data/user_de/0/<pkg>` is listable and
  `/data/data/<pkg>` does not exist yet.

  Turning it *on* is confirmed once, plainly, with what it costs stated. This
  removes a hearing protection and the dialog says so. Turning it back off is
  immediate and needs no confirmation.

- **Diagnostics reports it** — the request, the property, the platform's own
  state, and which of the two the user should believe when they disagree.

### Fixed

- **The Magisk module listed itself as "by VolumePerApp".** It is by
  **PortableDiag**; the app is not its own author. Reported from real use on
  2026-08-19 and parked as not worth a release of its own, which is why it rides
  along here.

### You do need to re-flash this one

Unlike 0.2.x, this release changes the module: `post-fs-data.sh` is new and the
suppression cannot work without it. The audio engine is unchanged.

## [0.2.2] — 2026-08-16

### Fixed

- **The "Privileged mode" banner was green in both themes.** On Ocean it turned
  green the moment anything was adjusted, clashing with an otherwise blue screen,
  and toggling the theme did not change it. It now wears the theme's own accent —
  blue under Ocean, green under Terminal — via `colorPrimary` /
  `colorPrimaryContainer`.

  This is a reversal. 0.2.1 fixed only the neutral banner and kept the success
  banner a fixed green *deliberately*, on the reasoning that status colours
  should read the same in any scheme. That reasoning was wrong for the success
  case: "working" is this app's normal state, not an exception, so it belongs in
  the theme's accent. Only genuine exceptions should break out of the scheme.

- **The "playing" badge was a fixed green** for the same reason and with the same
  result — a stray green pip on the blue theme. Now `colorSecondary`, which is
  cyan under Ocean and green under Terminal, and stays distinct from the
  `colorPrimary` "routed" badge beside it.

- The banner stripe's XML default was a fixed green too, visible for one frame
  before binding. Now `?attr/colorPrimary`.

**Still deliberately fixed, and verified against both themes:** the amber
**Limited mode** banner, the orange **boost clips** badge and the red **blocks
capture** badge. Those are exceptions — they have to alarm regardless of scheme,
and in an export with no palette at all. Amber was checked pixel-exact against
Terminal: `#FBBF24` on `#2E2208`, over a true-black page with a `#22DD6A` slider.

## [0.2.1] — 2026-08-16

Two things reported from real use on the OnePlus 12.

### Added

- **A reset button on each channel strip.** Puts the fader back to 100 % and
  unmutes, in one tap. It is hidden while the app is already at 100 % and
  unmuted, so it adds nothing to the common case and its presence is itself the
  signal that a strip has been touched.
- **A detent at 100 %.** Release the fader within 2 % of 100 and it settles
  exactly on 100. It snaps on release rather than mid-drag, so it does not feel
  sticky while you are moving it, and a value further out — 93 %, say — is left
  alone. This is the half that stops 98 % happening; the button is the half that
  fixes it after the fact.

  Both were prompted by the same complaint: a 1-step fader across 0–150 makes
  exactly 100 genuinely hard to hit, and 98 % or 103 % is not what anyone meant.

### Fixed

- **The neutral "Nothing is adjusted" banner ignored the theme toggle.** It
  resolved `R.color.surface_variant` and `R.color.text_secondary` directly, and
  those are Ocean's resources — Terminal's are separate names, not an override of
  the same ones — so the banner stayed Ocean-blue in Terminal. It now resolves
  `colorSurfaceVariant` / `colorOnSurfaceVariant` as **theme attributes**.

  The OK and WARN banners deliberately keep fixed green and amber: those encode
  status rather than decoration, and should read the same in either scheme and in
  a screenshot that lost its palette. Only the neutral one was wrong.

## [0.2.0] — 2026-08-16

**Confirmed working on real hardware** — a OnePlus 12 (`CPH2583`), Android 15 /
API 35, OxygenOS V15.0.0, Magisk 30.1. Per-app volume works there, not only on
the emulator, which retires the caveat every note in this project carried.

Both that phone and the emulator are **API 35**, the level the hidden call chain
was pinned against, so `RULE_MATCH_UID` and `ROUTE_FLAG_LOOP_BACK` are confirmed
on hardware rather than assumed to have survived a version difference. The
device is `arm64-v8a` against the emulator's `x86_64`, which is irrelevant here
— the app has no native code — but the vendor ROM was a real risk and is now
one data point retired.

No engine changes. Anyone already running the 0.1.0 module has the same audio
code and does not need to re-flash; this release exists to mark the source
being published and the emulator-only qualifier being gone.

### Changed

- Version bumped for the first public source release.
- `docs/screenshots/app-picker.png` removed, and scrubbed from git history: it
  was a screenshot of the emulator's app list and showed the package names of
  unrelated private projects. Nothing about VolumePerApp, and not mine to
  publish.
- README states plainly that the route is verified on real hardware.

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
