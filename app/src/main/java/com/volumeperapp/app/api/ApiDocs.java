package com.volumeperapp.app.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The API's own reference, served at {@code GET /api/docs}. It lives in the app
 * because the likeliest reader is an agent holding the key and the port and
 * nothing else; a reference it fetches cannot drift from the build answering.
 */
public final class ApiDocs {

    private ApiDocs() {
    }

    /** The one-line index at {@code GET /api}. A test checks every one is routed. */
    public static final List<String> SUMMARY = Collections.unmodifiableList(Arrays.asList(
            "GET    /api/health",
            "GET    /api/docs",
            "GET    /api/instance",
            "GET    /api/mixer",
            "GET    /api/apps",
            "GET    /api/apps/{package}",
            "POST   /api/apps/{package}",
            "PATCH  /api/apps/{package}",
            "DELETE /api/apps/{package}",
            "POST   /api/apps/{package}/reset",
            "POST   /api/reset",
            "GET    /api/installed?q=",
            "GET    /api/streams",
            "PATCH  /api/streams/{media|ring|alarm|notification|system}",
            "GET    /api/settings",
            "PATCH  /api/settings",
            "GET    /api/palettes",
            "GET    /api/diagnostics"
    ));

    public static final String MARKDOWN = """
# VolumePerApp local API

Per-app volume on this device over HTTP: every fader, mute and reset on the mixer, the system
streams, and the settings. Everything here is something the person holding the phone can
already do on the mixer screen — the API is a second pair of hands, not a second set of
features.

- **Base URL:** `http://127.0.0.1:<port>/api` — the port is shown in the app under
  **Local API** (default 8799). Loopback only; from a laptop, `adb forward tcp:8799 tcp:8799`.
- **Off by default.** It runs only while it is switched on in the app.
- **Auth:** every route but `GET /api/health` needs the key, as `X-API-Key: <key>` or
  `Authorization: Bearer <key>`. A new key can be issued in the app at any time and the old
  one stops working at once.
- **Errors** are JSON: `{"error": "...", "hint": "..."}` with a 4xx for a request that is
  wrong and a 5xx for the app's own failure. Never a silent 200.
- **Writes take JSON bodies.** An unknown field is ignored; a known one with a bad value is
  a 400 and nothing in the request is applied.

## How per-app volume works, in one paragraph

Android has no per-app volume. VolumePerApp is a privileged system app (installed by its
Magisk module) that diverts an app's audio into itself, scales it and plays it back out. An
app at **100 % is never diverted** — no cost, no latency — so 100 means "leave it alone".
Per-app faders only do anything in **privileged** mode; `GET /api/instance` says which mode
this device is in, and `GET /api/diagnostics` says why.

## Status

`GET /api/health` — no key. `{"ok": true, "app": "VolumePerApp", "api": 1}`.

`GET /api/docs` — this reference. `GET /api` — the one-line list of every route.

`GET /api/instance` — version, Android level, and the engine:
`mode` is `privileged` (faders are real), `idle` (nothing adjusted, engine off by design) or
`limited` (the routing permission is not granted; per-app faders are inert, system streams
still work). Also `engine_running`, `routed` (how many apps are diverted right now),
`playback_detection` (whether the notification-listener grant that lets the app see what is
playing is on — granting it needs a person, in system settings).

## The mixer

`GET /api/mixer` — what the screen shows: `mode`, then `apps` (playing ones first), then
`streams`.

An **app** is:

```json
{"package": "com.spotify.music", "label": "Spotify", "uid": 10234,
 "gain": 60, "muted": false, "playing": true, "routed": true,
 "capture_blocked": false, "pinned": true}
```

- `gain` — percent, 0 to the fader ceiling (`max_gain` in settings). 100 is unadjusted.
- `routed` — its audio is being diverted right now.
- `capture_blocked` — the app opted out of playback capture (Chrome does), so its fader
  **cannot work**. Setting it is accepted and does nothing; say so rather than retrying.
- Above 100 % is a real amplifier and **clips**.
- A fader is per uid: apps sharing a uid share one.

`GET /api/apps` — just the strips. `GET /api/apps/{package}` — one app, on the mixer or not
(404 if it is not installed).

`PATCH /api/apps/{package}` — `{"gain": 40}`, `{"muted": true}`, or both. Puts the app on the
mixer, as moving its fader does. The answer carries `engine`:
`applied` (the engine has it), or `deferred` — the engine was idle and Android refuses to
start it from the background; the setting is saved and takes effect the next time the app is
opened. Once any app is adjusted the engine is running, and later changes apply at once.

`POST /api/apps/{package}` — add to the mixer without changing anything (the + button).

`POST /api/apps/{package}/reset` — back to 100 % and unmuted (the strip's reset).

`DELETE /api/apps/{package}` — off the mixer, and its setting forgotten (the strip's X).

`POST /api/reset` — every fader back to 100 % and unmuted.

`GET /api/installed?q=spot` — the app picker: installed apps that can make a sound, by label,
with package and uid. `q` filters on label or package; without it, all of them.

## System streams

`GET /api/streams` — `[{"name": "media", "volume": 9, "max": 15}, ...]` for
`media`, `ring`, `alarm`, `notification`, `system`.

`PATCH /api/streams/{name}` — `{"volume": 7}`, in the stream's own steps (0 to `max`). Ring and
notification can be locked by Do Not Disturb; that is a 409.

## Settings

`GET /api/settings` —

```json
{"palette": "Ocean", "palettes": ["Material You", "Ocean", ...],
 "max_gain": 150, "max_gain_choices": [100, 150, 200, 400],
 "volume_warning": {"suppress_requested": false, "property": "unset",
                    "state": "ACTIVE", "suppressed": false, "takes_effect": "at restart"}}
```

`PATCH /api/settings` — any of:

- `palette` — one of the house palettes by name (`GET /api/palettes`). Case and spaces are
  ignored. The screen follows at once.
- `max_gain` — the fader ceiling, one of 100 (attenuate only, cannot clip), 150, 200, 400.
  Faders above a lowered ceiling keep their value until moved.
- `suppress_volume_warning` — `true` turns off Android's hearing-safety nag: both the "Volume
  alert" popup and the automatic "volume was lowered" drop, which are one mechanism.
  **It takes effect at the next restart, not now** — the platform reads it once at boot —
  and `volume_warning.suppressed` reports what the platform is doing this boot. This removes
  a hearing protection: sustained high volume through headphones causes permanent hearing
  loss. Tell the person before turning it on for them.

`GET /api/palettes` — `{"current": "Ocean", "palettes": [...]}`, in the picker's order.

## Diagnostics

`GET /api/diagnostics` — the Diagnostics screen as plain text: install, permissions, the
hidden-API chain, the engine with each routed app's **measured** gain, and the
hearing-safety state. It re-probes live by registering a throwaway audio policy, exactly as
the screen's Re-probe button does — **do not poll it**; ask when something looks wrong.
It names the packages being routed.

## What the API cannot do

- **Grant playback detection** (notification-listener access). Android only lets a person
  switch that on, in system settings.
- **Install the Magisk module.** Limited mode stays limited until a person flashes it.
""";
}
