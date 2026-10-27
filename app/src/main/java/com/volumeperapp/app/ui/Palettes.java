// Generated from the ProjectForge house palettes by tools/gen-palettes.py - do not edit here; change the palette with `pf palette`, then re-run it.
package com.volumeperapp.app.ui;

import com.volumeperapp.app.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The house palettes, in the picker's order. Saved by NAME, so adding one never
 * shifts a saved choice.
 */
public final class Palettes {

    public static final class Palette {
        public final String name;
        public final String label;
        public final int style;
        /** True for the palettes with a light and a dark form; the rest are one look. */
        public final boolean followsMode;
        /** For a fixed palette: whether that one look is dark. */
        public final boolean dark;
        public final boolean mono;

        Palette(String name, String label, int style, boolean followsMode, boolean dark,
                boolean mono) {
            this.name = name;
            this.label = label;
            this.style = style;
            this.followsMode = followsMode;
            this.dark = dark;
            this.mono = mono;
        }
    }

    public static final List<Palette> ALL = Collections.unmodifiableList(Arrays.asList(
            new Palette("Material You", "Material You (wallpaper colours)", R.style.Theme_VolumePerApp_MaterialYou, true, true, false),
            new Palette("Ocean", "Ocean", R.style.Theme_VolumePerApp_Ocean, true, true, false),
            new Palette("Terminal", "Terminal", R.style.Theme_VolumePerApp_Terminal, true, true, true),
            new Palette("Trellis", "Trellis", R.style.Theme_VolumePerApp_Trellis, false, true, false),
            new Palette("Graphite", "Graphite", R.style.Theme_VolumePerApp_Graphite, false, true, false),
            new Palette("Blueprint", "Blueprint", R.style.Theme_VolumePerApp_Blueprint, false, true, false),
            new Palette("Futuristic", "Futuristic", R.style.Theme_VolumePerApp_Futuristic, false, true, false),
            new Palette("Neon", "Neon", R.style.Theme_VolumePerApp_Neon, false, true, false),
            new Palette("SynthWave", "SynthWave", R.style.Theme_VolumePerApp_SynthWave, false, true, false),
            new Palette("White Glow", "White Glow", R.style.Theme_VolumePerApp_WhiteGlow, false, true, false),
            new Palette("Silkscreen", "Silkscreen", R.style.Theme_VolumePerApp_Silkscreen, false, true, false),
            new Palette("Sticky Notes", "Sticky Notes", R.style.Theme_VolumePerApp_StickyNotes, false, false, false),
            new Palette("Amber Terminal", "Amber Terminal", R.style.Theme_VolumePerApp_AmberTerminal, false, true, true),
            new Palette("Phosphor Green", "Phosphor Green", R.style.Theme_VolumePerApp_PhosphorGreen, false, true, true),
            new Palette("Phosphor P31", "Phosphor P31", R.style.Theme_VolumePerApp_PhosphorP31, false, true, true),
            new Palette("Zoro", "Zoro", R.style.Theme_VolumePerApp_Zoro, false, true, false),
            new Palette("Nebula", "Nebula", R.style.Theme_VolumePerApp_Nebula, false, true, false)
    ));

    private Palettes() {
    }
}
