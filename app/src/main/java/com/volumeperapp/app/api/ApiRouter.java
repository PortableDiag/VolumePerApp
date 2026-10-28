package com.volumeperapp.app.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The API's rules: which path means what, what each accepts, and which
 * failures are the caller's fault rather than the app's.
 *
 * <p>No Android type reaches this file — every route is exercised by unit tests
 * against a fake {@link ApiBackend}. Two conventions run through it, both from
 * Lull's API, which this one copies:
 *
 * <ul>
 *   <li><b>Every route is something the mixer screen can already do.</b> The
 *       API is a second pair of hands, not a second set of features.</li>
 *   <li><b>A wrong request gets a status and a sentence</b>, never a stack trace
 *       and never a silent 200.</li>
 * </ul>
 */
public final class ApiRouter {

    /** Bumped when a response shape changes in a way a caller could notice. */
    public static final int API_VERSION = 1;

    /** Answered without a key: liveness, so a watchdog needs no credential. */
    private static final String OPEN_PATH = "/api/health";

    public static final List<String> STREAMS = Collections.unmodifiableList(
            Arrays.asList("media", "ring", "alarm", "notification", "system"));

    public static final List<Integer> CEILINGS = Collections.unmodifiableList(
            Arrays.asList(100, 150, 200, 400));

    private final ApiBackend backend;
    private final Supplier<String> expectedKey;
    private final Supplier<PhoneApi.Grant> phoneApi;

    /** Who a request's key belongs to: the owner's key, or the one PhoneAPI was granted. */
    private enum Caller { NOBODY, OWNER, PHONEAPI }

    public ApiRouter(ApiBackend backend, Supplier<String> expectedKey) {
        this(backend, expectedKey, () -> null);
    }

    public ApiRouter(ApiBackend backend, Supplier<String> expectedKey,
                     Supplier<PhoneApi.Grant> phoneApi) {
        this.backend = backend;
        this.expectedKey = expectedKey;
        this.phoneApi = phoneApi;
    }

    /**
     * Every answer — health, 401s, 404s and errors too — carries PhoneAPI's
     * proof once it holds a proof key (provider contract §2), so PhoneAPI can
     * tell VolumePerApp from whatever else might have bound the port.
     */
    public Http.Response handle(Http.Request r) {
        Http.Response out = route(r);
        String challenge = PhoneApi.usableChallenge(r.header(PhoneApi.HEADER_CHALLENGE));
        PhoneApi.Grant g = challenge == null ? null : phoneApi.get();
        if (g == null) return out;
        return out.withHeader(PhoneApi.HEADER_PROOF, PhoneApi.proof(g.proofKey, challenge));
    }

    private Http.Response route(Http.Request r) {
        try {
            if ("OPTIONS".equals(r.method)) return Http.Response.noContent();
            if (!r.path.equals("/api") && !r.path.startsWith("/api/")) {
                return Http.Response.error(404, "not an API path",
                        "everything this server answers lives under /api — GET /api/docs lists it");
            }
            if (!r.path.equals(OPEN_PATH)) {
                Caller who = caller(r);
                if (who == Caller.NOBODY) {
                    return Http.Response.error(401, "missing or invalid API key",
                            "send it as the X-API-Key header, or Authorization: Bearer <key>");
                }
                if (who == Caller.PHONEAPI) {
                    PhoneApi.Grant g = phoneApi.get();
                    boolean reading = "GET".equals(r.method);
                    if (g == null || (reading ? !g.see : !g.change)) {
                        return Http.Response.error(403, "PhoneAPI's key may not "
                                        + (reading ? "see" : "change") + " anything in VolumePerApp",
                                "the owner chose what it may do; Connect in PhoneAPI "
                                        + "(Providers, VolumePerApp) asks again");
                    }
                }
            }
            return dispatch(r);
        } catch (ApiException e) {
            return Http.Response.error(e.status, e.getMessage(), e.hint);
        } catch (Exception e) {
            return Http.Response.error(500, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null);
        }
    }

    /**
     * Constant-time: the timing of a string compare leaks how much of a key is
     * right, and a local socket is where an attacker can afford the tries.
     */
    private Caller caller(Http.Request r) {
        String offered = r.header("x-api-key");
        if (offered == null) {
            String auth = r.header("authorization");
            if (auth == null) return Caller.NOBODY;
            offered = auth.replaceFirst("^[Bb]earer\\s+", "").trim();
        }
        if (same(offered, expectedKey.get())) return Caller.OWNER;
        PhoneApi.Grant g = phoneApi.get();
        if (g != null && same(offered, g.key)) return Caller.PHONEAPI;
        return Caller.NOBODY;
    }

    private static boolean same(String offered, String expected) {
        if (expected == null || expected.isEmpty()) return false;
        byte[] a = offered.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // ----------------------------------------------------------------- dispatch

    private Http.Response dispatch(Http.Request r) throws Exception {
        List<String> seg = new ArrayList<>();
        for (String s : r.path.substring("/api".length()).split("/")) {
            if (!s.isEmpty()) seg.add(s);
        }
        if (seg.isEmpty()) return get(r, this::index);

        String head = seg.get(0);
        List<String> rest = seg.subList(1, seg.size());
        switch (head) {
            case "health":
                return get(r, () -> Http.Response.json(new JSONObject()
                        .put("ok", true).put("app", "VolumePerApp").put("api", API_VERSION)));
            case "docs":
                return get(r, () -> Http.Response.text(ApiDocs.MARKDOWN,
                        "text/markdown; charset=utf-8"));
            case "instance":
                return get(r, () -> Http.Response.json(backend.instance()));
            case "mixer":
                return get(r, () -> Http.Response.json(backend.mixer()));
            case "apps":
                return apps(r, rest);
            case "installed":
                return get(r, () -> Http.Response.json(backend.installed(r.query.get("q"))));
            case "reset":
                return post(r, () -> {
                    backend.resetAll();
                    return ok();
                });
            case "streams":
                return streams(r, rest);
            case "settings":
                return settings(r);
            case "palettes":
                return get(r, () -> Http.Response.json(new JSONObject()
                        .put("current", backend.palette())
                        .put("palettes", new JSONArray(backend.palettes()))));
            case "diagnostics":
                return get(r, () -> Http.Response.text(backend.diagnostics(),
                        "text/plain; charset=utf-8"));
            default:
                return noRoute(r);
        }
    }

    private Http.Response index() throws Exception {
        return Http.Response.json(new JSONObject()
                .put("app", "VolumePerApp")
                .put("api", API_VERSION)
                .put("docs", "/api/docs")
                .put("endpoints", new JSONArray(ApiDocs.SUMMARY)));
    }

    // --------------------------------------------------------------------- apps

    private Http.Response apps(Http.Request r, List<String> rest) throws Exception {
        if (rest.isEmpty()) {
            return get(r, () -> Http.Response.json(backend.mixer().getJSONArray("apps")));
        }
        String pkg = rest.get(0);
        if (!isPackageName(pkg)) {
            throw new ApiException(400, "not a package name: " + pkg,
                    "an app is addressed by its package, e.g. /api/apps/com.spotify.music");
        }
        if (rest.size() == 2 && "reset".equals(rest.get(1))) {
            return post(r, () -> {
                requireInstalled(pkg);
                return ok("engine", engine(backend.reset(pkg)), "app", backend.app(pkg));
            });
        }
        if (rest.size() != 1) return noRoute(r);

        switch (r.method) {
            case "GET":
                return Http.Response.json(requireApp(pkg));
            case "POST":
                requireInstalled(pkg);
                backend.pin(pkg);
                return ok("app", backend.app(pkg));
            case "PUT":
            case "PATCH":
                return patchApp(r, pkg);
            case "DELETE":
                requireInstalled(pkg);
                backend.remove(pkg);
                return ok();
            default:
                return methodNotAllowed(r, "GET", "POST", "PATCH", "PUT", "DELETE");
        }
    }

    private Http.Response patchApp(Http.Request r, String pkg) throws Exception {
        requireInstalled(pkg);
        JSONObject body = r.json();
        Integer gain = null;
        Boolean muted = null;
        if (body.has("gain")) {
            Object raw = body.get("gain");
            int max = backend.maxGain();
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() % 1 != 0) {
                throw new ApiException(400, "gain must be a whole percent",
                        "0 to " + max + " — the fader ceiling; 100 leaves the app alone");
            }
            gain = ((Number) raw).intValue();
            if (gain < 0 || gain > max) {
                throw new ApiException(400, "gain " + gain + " is outside 0–" + max,
                        "the fader ceiling is " + max + " %; raise it with PATCH /api/settings "
                                + "{\"max_gain\": 200}");
            }
        }
        if (body.has("muted")) {
            Object raw = body.get("muted");
            if (!(raw instanceof Boolean)) {
                throw new ApiException(400, "muted must be true or false", null);
            }
            muted = (Boolean) raw;
        }
        if (gain == null && muted == null) {
            throw new ApiException(400, "nothing to change",
                    "send {\"gain\": 0–" + backend.maxGain() + "} and/or {\"muted\": true|false}");
        }
        boolean applied = backend.setGain(pkg, gain, muted);
        return ok("engine", engine(applied), "app", backend.app(pkg));
    }

    private JSONObject requireApp(String pkg) throws Exception {
        JSONObject app = backend.app(pkg);
        if (app == null) throw notInstalled(pkg);
        return app;
    }

    private void requireInstalled(String pkg) throws ApiException {
        if (!backend.isInstalled(pkg)) throw notInstalled(pkg);
    }

    private static ApiException notInstalled(String pkg) {
        return new ApiException(404, "no installed app " + pkg,
                "GET /api/installed?q=<name> finds an app's package");
    }

    /** Dots and the characters a package may hold — nothing that could walk a path. */
    static boolean isPackageName(String s) {
        return s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /**
     * What happened to a fader change. "deferred" is the one honest answer when
     * the engine was idle and Android would not let it start from the
     * background: the setting is saved and takes effect when the app is opened.
     */
    private static String engine(boolean applied) {
        return applied ? "applied" : "deferred";
    }

    // ------------------------------------------------------------------ streams

    private Http.Response streams(Http.Request r, List<String> rest) throws Exception {
        if (rest.isEmpty()) return get(r, () -> Http.Response.json(backend.streams()));
        if (rest.size() != 1) return noRoute(r);
        String name = rest.get(0);
        if (!STREAMS.contains(name)) {
            throw new ApiException(404, "no stream " + name, "one of " + STREAMS);
        }
        if (!"PUT".equals(r.method) && !"PATCH".equals(r.method)) {
            return methodNotAllowed(r, "PATCH", "PUT");
        }
        Object raw = r.json().opt("volume");
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() % 1 != 0) {
            throw new ApiException(400, "volume must be a whole step",
                    "GET /api/streams gives each stream's max");
        }
        backend.setStream(name, ((Number) raw).intValue());
        return ok("streams", backend.streams());
    }

    // ----------------------------------------------------------------- settings

    private Http.Response settings(Http.Request r) throws Exception {
        if ("GET".equals(r.method)) return Http.Response.json(settingsJson());
        if (!"PATCH".equals(r.method) && !"PUT".equals(r.method)) {
            return methodNotAllowed(r, "GET", "PATCH");
        }
        JSONObject body = r.json();
        // Validate everything before changing anything, so a bad field cannot
        // leave half a request applied.
        String palette = null;
        if (body.has("palette")) {
            String want = body.optString("palette", "");
            for (String p : backend.palettes()) {
                if (squash(p).equals(squash(want))) palette = p;
            }
            if (palette == null) {
                throw new ApiException(400, "no palette " + want,
                        "one of " + backend.palettes());
            }
        }
        Integer ceiling = null;
        if (body.has("max_gain")) {
            Object raw = body.get("max_gain");
            if (!(raw instanceof Number) || !CEILINGS.contains(((Number) raw).intValue())
                    || ((Number) raw).doubleValue() % 1 != 0) {
                throw new ApiException(400, "max_gain must be one of " + CEILINGS,
                        "100 attenuates only and cannot clip; the rest boost and can");
            }
            ceiling = ((Number) raw).intValue();
        }
        Boolean suppress = null;
        if (body.has("suppress_volume_warning")) {
            Object raw = body.get("suppress_volume_warning");
            if (!(raw instanceof Boolean)) {
                throw new ApiException(400, "suppress_volume_warning must be true or false", null);
            }
            suppress = (Boolean) raw;
        }
        if (palette == null && ceiling == null && suppress == null) {
            throw new ApiException(400, "nothing to change",
                    "settable: palette, max_gain, suppress_volume_warning");
        }

        if (palette != null) backend.setPalette(palette);
        if (ceiling != null) backend.setMaxGain(ceiling);
        if (suppress != null && !backend.setSafeVolumeRequested(suppress)) {
            throw new ApiException(500, "could not record the volume-warning setting",
                    "the setting is unchanged; GET /api/diagnostics says why");
        }
        return ok("settings", settingsJson());
    }

    private JSONObject settingsJson() throws Exception {
        return new JSONObject()
                .put("palette", backend.palette())
                .put("palettes", new JSONArray(backend.palettes()))
                .put("max_gain", backend.maxGain())
                .put("max_gain_choices", new JSONArray(CEILINGS))
                .put("volume_warning", backend.safeVolume());
    }

    private static String squash(String s) {
        return s.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ helpers

    private interface Body {
        Http.Response get() throws Exception;
    }

    private Http.Response get(Http.Request r, Body body) throws Exception {
        return "GET".equals(r.method) ? body.get() : methodNotAllowed(r, "GET");
    }

    private Http.Response post(Http.Request r, Body body) throws Exception {
        return "POST".equals(r.method) ? body.get() : methodNotAllowed(r, "POST");
    }

    private static Http.Response ok(Object... pairs) throws Exception {
        JSONObject obj = new JSONObject().put("ok", true);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            obj.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
        }
        return Http.Response.json(obj);
    }

    private static Http.Response methodNotAllowed(Http.Request r, String... allowed) {
        String allow = String.join(", ", allowed);
        Http.Response e = Http.Response.error(405, r.method + " not allowed on " + r.path,
                "allowed: " + allow);
        return new Http.Response(405, e.contentType, e.body,
                Collections.singletonMap("Allow", allow));
    }

    private static Http.Response noRoute(Http.Request r) {
        return Http.Response.error(404, "no route for " + r.method + " " + r.path,
                "GET /api/docs lists every endpoint");
    }
}
