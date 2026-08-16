package com.volumeperapp.app.data;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The installed-app side of the mixer: names, icons and the uid the routing
 * engine actually matches on.
 *
 * <p>Labels and icons are cached because the picker asks for every installed
 * package at once and {@code loadLabel} hits the resource table each time.
 */
public final class AppRepository {

    private static final String TAG = "VPA.Apps";

    private final Context context;
    private final PackageManager pm;
    private final Map<String, String> labelCache = new HashMap<>();

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    public String labelFor(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, "no label for " + pkg);
        }
        labelCache.put(pkg, label);
        return label;
    }

    public int uidFor(String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    public String packageForUid(int uid) {
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return null;
        // A shared uid returns several; the first is as good as any and the
        // fader genuinely applies to all of them, since routing is per-uid.
        return pkgs[0];
    }

    /**
     * {@code ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE}, read
     * off API 35 by {@code tools/probe/Probe2.java}. Only used if the hidden
     * accessor below is missing.
     */
    private static final int PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE = 1 << 27;

    /**
     * Whether this app's audio can be diverted at all.
     *
     * <p>An app that sets {@code android:allowAudioPlaybackCapture="false"}
     * cannot be captured by a loopback mix, so its fader would move and change
     * nothing. The mixer marks those rather than showing a control that lies.
     *
     * <p>The honest alternative would be a privileged-capture mix, which the
     * platform caps at 16 kHz mono — see the note in {@code AudioPolicyBridge}.
     * Silence with a warning beats every app sounding like a phone call.
     *
     * <p>Unknown counts as capturable: a false warning on an app that works is
     * worse than no warning on one that does not, because the second is visible
     * the moment you try it.
     */
    public boolean captureAllowed(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            try {
                Object v = ApplicationInfo.class
                        .getDeclaredMethod("isAudioPlaybackCaptureAllowed")
                        .invoke(ai);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {
                // Hidden accessor absent; fall through to the flag.
            }
            java.lang.reflect.Field f = ApplicationInfo.class.getDeclaredField("privateFlags");
            f.setAccessible(true);
            int flags = f.getInt(ai);
            return (flags & PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    public android.graphics.drawable.Drawable iconFor(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }

    /**
     * Every app that can plausibly make a sound — anything with a launcher entry
     * or a media-ish permission. Sorted by label.
     *
     * <p>The bar is deliberately low: an app that surprises you with a sound is
     * exactly the one you came here to turn down, so a false positive in this
     * list costs nothing and a false negative costs the whole feature.
     */
    public List<AppEntry> candidateApps() {
        List<AppEntry> out = new ArrayList<>();
        String self = context.getPackageName();
        List<ApplicationInfo> all = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : all) {
            if (ai.packageName.equals(self)) continue;
            boolean launchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!launchable && system) continue;
            AppEntry e = new AppEntry(ai.packageName,
                    String.valueOf(pm.getApplicationLabel(ai)), ai.uid);
            out.add(e);
            labelCache.put(e.packageName, e.label);
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    public AppEntry entryFor(String pkg) {
        int uid = uidFor(pkg);
        if (uid < 0) return null;
        return new AppEntry(pkg, labelFor(pkg), uid);
    }
}
