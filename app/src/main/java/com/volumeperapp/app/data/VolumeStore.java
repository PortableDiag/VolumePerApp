package com.volumeperapp.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a fader position lives between runs.
 *
 * <p>Keyed by package name, not uid: uids are reassigned on reinstall and are
 * shared by everything in a {@code sharedUserId}, so a uid key would silently
 * move one app's setting onto another. The engine resolves package to uid at
 * the moment it routes.
 */
public final class VolumeStore {

    private static final String PREFS = "mixer";
    private static final String KEY_GAIN = "gain.";
    private static final String KEY_MUTED = "muted.";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_THEME = "theme";
    private static final String KEY_ENGINE = "engine_enabled";
    private static final String KEY_MAX = "max_gain_percent";

    /** 100 % means "leave it alone" and is the one value that is never routed. */
    public static final int DEFAULT_PERCENT = 100;

    private final SharedPreferences prefs;

    public VolumeStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int gainPercent(String pkg) {
        return prefs.getInt(KEY_GAIN + pkg, DEFAULT_PERCENT);
    }

    public void setGainPercent(String pkg, int percent) {
        prefs.edit().putInt(KEY_GAIN + pkg, percent).apply();
    }

    public boolean isMuted(String pkg) {
        return prefs.getBoolean(KEY_MUTED + pkg, false);
    }

    public void setMuted(String pkg, boolean muted) {
        prefs.edit().putBoolean(KEY_MUTED + pkg, muted).apply();
    }

    /** Effective linear gain: 0 when muted, else percent/100. */
    public float effectiveGain(String pkg) {
        if (isMuted(pkg)) return 0f;
        return gainPercent(pkg) / 100f;
    }

    /** True when this app needs the routing engine at all. */
    public boolean needsRouting(String pkg) {
        return isMuted(pkg) || gainPercent(pkg) != DEFAULT_PERCENT;
    }

    // ---- the user's chosen set of apps (the mixer's channel strips) ---------

    public Set<String> pinned() {
        return new LinkedHashSet<>(prefs.getStringSet(KEY_PINNED, Collections.emptySet()));
    }

    public void pin(String pkg) {
        Set<String> s = pinned();
        if (s.add(pkg)) prefs.edit().putStringSet(KEY_PINNED, s).apply();
    }

    public void unpin(String pkg) {
        Set<String> s = pinned();
        if (s.remove(pkg)) {
            prefs.edit()
                    .putStringSet(KEY_PINNED, s)
                    .remove(KEY_GAIN + pkg)
                    .remove(KEY_MUTED + pkg)
                    .apply();
        }
    }

    /** Every package with a non-default setting, whether pinned or not. */
    public List<String> adjustedPackages() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(KEY_GAIN)) {
                String pkg = k.substring(KEY_GAIN.length());
                if (needsRouting(pkg)) out.add(pkg);
            } else if (k.startsWith(KEY_MUTED) && Boolean.TRUE.equals(e.getValue())) {
                String pkg = k.substring(KEY_MUTED.length());
                if (!out.contains(pkg)) out.add(pkg);
            }
        }
        return out;
    }

    public void resetAll() {
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith(KEY_GAIN) || k.startsWith(KEY_MUTED)) ed.remove(k);
        }
        ed.apply();
    }

    // ---- app-wide settings -------------------------------------------------

    /** "ocean" or "terminal". */
    public String theme() {
        return prefs.getString(KEY_THEME, "ocean");
    }

    public void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme).apply();
    }

    public boolean engineEnabled() {
        return prefs.getBoolean(KEY_ENGINE, true);
    }

    public void setEngineEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENGINE, enabled).apply();
    }

    /**
     * Ceiling for the faders. 100 is attenuate-only and cannot clip; the higher
     * settings are a real amplifier and will, which is why this is a choice.
     */
    public int maxGainPercent() {
        return prefs.getInt(KEY_MAX, 150);
    }

    public void setMaxGainPercent(int max) {
        prefs.edit().putInt(KEY_MAX, max).apply();
    }
}
