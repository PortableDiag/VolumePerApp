package com.volumeperapp.app.api;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import com.volumeperapp.app.BuildConfig;
import com.volumeperapp.app.audio.MixerService;
import com.volumeperapp.app.audio.PlaybackWatcher;
import com.volumeperapp.app.audio.RoutingEngine;
import com.volumeperapp.app.audio.SafeVolume;
import com.volumeperapp.app.data.AppEntry;
import com.volumeperapp.app.data.AppRepository;
import com.volumeperapp.app.data.VolumeStore;
import com.volumeperapp.app.ui.DiagnosticsActivity;
import com.volumeperapp.app.ui.Themes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The API's hands: each method does what the mixer screen does for the same
 * tap, through the same store and the same engine, so a fader an agent moves
 * is indistinguishable from one moved by a finger — the screen redraws from
 * the store either way.
 *
 * <p>Requests arrive on worker threads. The store is SharedPreferences and the
 * engine is synchronized, so the only thing this class keeps per call is an
 * {@link AppRepository}, whose label cache is not thread-safe.
 */
final class VolumePerAppBackend implements ApiBackend {

    private static final int[] STREAM_TYPES = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
    };

    private final Context context;
    private final VolumeStore store;
    private final AudioManager audio;

    VolumePerAppBackend(Context context) {
        this.context = context.getApplicationContext();
        this.store = new VolumeStore(this.context);
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    private RoutingEngine engine() {
        return MixerService.engine(context);
    }

    // ------------------------------------------------------------------ status

    @Override
    public JSONObject instance() throws Exception {
        RoutingEngine eng = engine();
        return new JSONObject()
                .put("app", "VolumePerApp")
                .put("version", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE)
                .put("api", ApiRouter.API_VERSION)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("mode", mode(eng))
                .put("engine_running", eng.isRunning())
                .put("engine_status", eng.status())
                .put("routed", eng.routedUids().size())
                .put("playback_detection", eng.watcher().sessionAccessGranted());
    }

    /** The banner's three states, decided exactly as the banner decides them. */
    private String mode(RoutingEngine eng) {
        if (eng.isRunning() && eng.isPrivileged()) return "privileged";
        if (store.adjustedPackages().isEmpty() && !eng.isRunning()) return "idle";
        return "limited";
    }

    // ------------------------------------------------------------------- mixer

    @Override
    public JSONObject mixer() throws Exception {
        RoutingEngine eng = engine();
        AppRepository apps = new AppRepository(context);
        PlaybackWatcher watcher = eng.watcher();
        // While the screen is up or the engine is routing, the watcher's
        // callback keeps this current. Otherwise read it once: refreshing an
        // unstarted watcher notifies nobody.
        if (!eng.isWatching()) watcher.refresh();
        Set<Integer> activeUids = watcher.activeUids();
        Set<String> activePkgs = watcher.activePackages();

        Set<String> wanted = new LinkedHashSet<>(activePkgs);
        for (Integer uid : activeUids) {
            String pkg = apps.packageForUid(uid);
            if (pkg != null && !pkg.equals(context.getPackageName())) wanted.add(pkg);
        }
        wanted.addAll(store.pinned());
        wanted.addAll(store.adjustedPackages());

        List<JSONObject> playing = new ArrayList<>();
        List<JSONObject> rest = new ArrayList<>();
        Set<Integer> routed = eng.routedUids();
        Set<String> pinned = store.pinned();
        for (String pkg : wanted) {
            AppEntry e = apps.entryFor(pkg);
            if (e == null) continue;
            boolean isPlaying = activePkgs.contains(pkg) || activeUids.contains(e.uid);
            JSONObject j = appJson(apps, e, isPlaying, routed.contains(e.uid), pinned);
            (isPlaying ? playing : rest).add(j);
        }
        JSONArray strips = new JSONArray();
        for (JSONObject j : playing) strips.put(j);
        for (JSONObject j : rest) strips.put(j);

        return new JSONObject()
                .put("mode", mode(eng))
                .put("max_gain", store.maxGainPercent())
                .put("apps", strips)
                .put("streams", streams());
    }

    @Override
    public JSONObject app(String pkg) throws Exception {
        AppRepository apps = new AppRepository(context);
        AppEntry e = apps.entryFor(pkg);
        if (e == null) return null;
        RoutingEngine eng = engine();
        PlaybackWatcher w = eng.watcher();
        if (!eng.isWatching()) w.refresh();
        boolean playing = w.activePackages().contains(pkg) || w.activeUids().contains(e.uid);
        return appJson(apps, e, playing, eng.routedUids().contains(e.uid), store.pinned());
    }

    private JSONObject appJson(AppRepository apps, AppEntry e, boolean playing, boolean routed,
                               Set<String> pinned) throws Exception {
        return new JSONObject()
                .put("package", e.packageName)
                .put("label", e.label)
                .put("uid", e.uid)
                .put("gain", store.gainPercent(e.packageName))
                .put("muted", store.isMuted(e.packageName))
                .put("playing", playing)
                .put("routed", routed)
                .put("capture_blocked", !apps.captureAllowed(e.packageName))
                .put("pinned", pinned.contains(e.packageName));
    }

    @Override
    public JSONArray installed(String query) throws Exception {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        for (AppEntry e : new AppRepository(context).candidateApps()) {
            if (!q.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(q)
                    && !e.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            out.put(new JSONObject()
                    .put("package", e.packageName)
                    .put("label", e.label)
                    .put("uid", e.uid));
        }
        return out;
    }

    @Override
    public boolean isInstalled(String pkg) {
        return new AppRepository(context).uidFor(pkg) >= 0;
    }

    // ------------------------------------------------------------------ faders

    @Override
    public boolean setGain(String pkg, Integer percent, Boolean muted) {
        if (percent != null) store.setGainPercent(pkg, percent);
        if (muted != null) store.setMuted(pkg, muted);
        store.pin(pkg);
        return MixerService.pushGain(context, pkg);
    }

    @Override
    public void pin(String pkg) {
        store.pin(pkg);
    }

    @Override
    public void remove(String pkg) {
        store.unpin(pkg);
        settle();
    }

    @Override
    public boolean reset(String pkg) {
        store.setGainPercent(pkg, VolumeStore.DEFAULT_PERCENT);
        store.setMuted(pkg, false);
        return MixerService.pushGain(context, pkg);
    }

    @Override
    public void resetAll() {
        store.resetAll();
        settle();
    }

    /** What the screen does after an X or a reset-all. */
    private void settle() {
        MixerService.sync(context);
        RoutingEngine eng = engine();
        if (eng.isRunning()) eng.syncRouting();
    }

    @Override
    public int maxGain() {
        return store.maxGainPercent();
    }

    @Override
    public void setMaxGain(int percent) {
        store.setMaxGainPercent(percent);
    }

    // ----------------------------------------------------------------- streams

    @Override
    public JSONArray streams() throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < STREAM_TYPES.length; i++) {
            out.put(new JSONObject()
                    .put("name", ApiRouter.STREAMS.get(i))
                    .put("volume", audio.getStreamVolume(STREAM_TYPES[i]))
                    .put("max", audio.getStreamMaxVolume(STREAM_TYPES[i])));
        }
        return out;
    }

    @Override
    public void setStream(String name, int volume) throws ApiException {
        int type = STREAM_TYPES[ApiRouter.STREAMS.indexOf(name)];
        int max = audio.getStreamMaxVolume(type);
        if (volume < 0 || volume > max) {
            throw new ApiException(400, name + " volume " + volume + " is outside 0–" + max, null);
        }
        try {
            audio.setStreamVolume(type, volume, 0);
        } catch (SecurityException e) {
            throw new ApiException(409, name + " is locked by Do Not Disturb policy",
                    "the same refusal the screen shows; it lifts when Do Not Disturb is off");
        }
    }

    // ---------------------------------------------------------------- settings

    @Override
    public String palette() {
        return Themes.current(store).name;
    }

    @Override
    public void setPalette(String name) {
        store.setTheme(name);
    }

    @Override
    public List<String> palettes() {
        return Collections.unmodifiableList(Themes.names());
    }

    @Override
    public JSONObject safeVolume() throws Exception {
        String prop = SafeVolume.property();
        return new JSONObject()
                .put("suppress_requested", SafeVolume.isRequested(context))
                .put("property", prop == null || prop.isEmpty() ? "unset" : prop)
                .put("state", SafeVolume.stateName(SafeVolume.state(context)))
                .put("suppressed", SafeVolume.isSuppressed(context))
                .put("takes_effect", "at restart");
    }

    @Override
    public boolean setSafeVolumeRequested(boolean requested) {
        return SafeVolume.setRequested(context, requested);
    }

    @Override
    public String diagnostics() {
        return DiagnosticsActivity.report(context);
    }
}
