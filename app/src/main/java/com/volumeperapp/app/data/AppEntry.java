package com.volumeperapp.app.data;

import android.graphics.drawable.Drawable;

/** One channel strip on the mixer: an app, its uid, and where its fader sits. */
public final class AppEntry {

    public final String packageName;
    public final String label;
    public final int uid;
    public Drawable icon;

    public int gainPercent = VolumeStore.DEFAULT_PERCENT;
    public boolean muted;

    /** Audio is coming out of this app right now. */
    public boolean playing;

    /** The engine currently holds a mix for this uid. */
    public boolean routed;

    /**
     * This app opted out of playback capture, so a loopback mix would divert it
     * into silence. The fader is shown but marked, never hidden — the user chose
     * to add this app and deserves to know why it will not move.
     */
    public boolean captureBlocked;

    public AppEntry(String packageName, String label, int uid) {
        this.packageName = packageName;
        this.label = label;
        this.uid = uid;
    }

    public boolean needsRouting() {
        return muted || gainPercent != VolumeStore.DEFAULT_PERCENT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppEntry)) return false;
        return packageName.equals(((AppEntry) o).packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }
}
