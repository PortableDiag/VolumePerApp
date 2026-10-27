package com.volumeperapp.app.api;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Whether the local API is on, what key opens it, and which port it uses.
 *
 * <p>Its own preferences file rather than the mixer's, so the mixer screen,
 * which redraws on every change to its store, never sees these. Two rules:
 * <ul>
 *   <li><b>Off until someone turns it on</b>, and turning it off stops it. A
 *       volume mixer that quietly opens a socket is not something anyone
 *       asked for.</li>
 *   <li><b>The key is generated, never typed.</b> It guards every fader on
 *       the phone and the hearing-safety switch.</li>
 * </ul>
 * There is no LAN switch: it binds loopback, always. PhoneAPI is the network
 * side; from a laptop, {@code adb forward} reaches it.
 */
public final class ApiPrefs {

    private static final String PREFS = "api";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_KEY = "key";
    private static final String KEY_PORT = "port";

    /** Clear of the other in-house apps' APIs (Lull 8787 … ThereminX 8796). */
    public static final int DEFAULT_PORT = 8799;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    public static final String BIND_ADDRESS = "127.0.0.1";

    /** 24 bytes of SecureRandom, hex: long enough that guessing is not a strategy. */
    private static final int KEY_BYTES = 24;

    private ApiPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean on) {
        // On with no key would be an open door: make one in the same breath.
        if (on && key(c).isEmpty()) regenerateKey(c);
        sp(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static String key(Context c) {
        String k = sp(c).getString(KEY_KEY, "");
        return k == null ? "" : k;
    }

    public static String regenerateKey(Context c) {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String key = sb.toString();
        // commit, not apply: the old key must be dead by the time the toast says so.
        sp(c).edit().putString(KEY_KEY, key).commit();
        return key;
    }

    public static int port(Context c) {
        int p = sp(c).getInt(KEY_PORT, DEFAULT_PORT);
        return Math.max(MIN_PORT, Math.min(MAX_PORT, p));
    }

    public static void setPort(Context c, int port) {
        sp(c).edit().putInt(KEY_PORT, Math.max(MIN_PORT, Math.min(MAX_PORT, port))).apply();
    }
}
