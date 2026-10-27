package com.volumeperapp.app.ui;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;
import com.volumeperapp.app.data.VolumeStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Which house palette is in force, and putting it on an Activity.
 *
 * <p>The list itself is generated ({@link Palettes}); this is the part that is
 * not. Two rules from the house palettes live here: a palette is saved by its
 * name, and a palette with one look sets night mode from its own luminance
 * rather than the system's — otherwise the semantic colours, which have a
 * values-night twin, would come out in the wrong form on it.
 */
public final class Themes {

    /** What a fresh install shows, and what an unknown saved name falls back to. */
    public static final String DEFAULT = "Ocean";

    private Themes() {
    }

    /**
     * The palette with this name, ignoring case and spaces — so the
     * {@code "terminal"} and {@code "ocean"} saved before 0.4.0 still match —
     * or null.
     */
    public static Palettes.Palette find(String name) {
        if (name == null) return null;
        String want = squash(name);
        for (Palettes.Palette p : Palettes.ALL) {
            if (squash(p.name).equals(want)) return p;
        }
        return null;
    }

    public static Palettes.Palette current(VolumeStore store) {
        Palettes.Palette p = find(store.theme());
        return p != null ? p : find(DEFAULT);
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Palettes.Palette p : Palettes.ALL) out.add(p.name);
        return out;
    }

    /** Before {@code super.onCreate}, so the first frame is already right. */
    public static void apply(AppCompatActivity activity, VolumeStore store) {
        Palettes.Palette p = current(store);
        activity.getDelegate().setLocalNightMode(p.followsMode
                ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : p.dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        activity.setTheme(p.style);
        if (p.style == com.volumeperapp.app.R.style.Theme_VolumePerApp_MaterialYou) {
            DynamicColors.applyToActivityIfAvailable(activity);
        }
    }

    private static String squash(String s) {
        return s.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }
}
