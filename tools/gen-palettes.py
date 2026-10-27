#!/usr/bin/env python3
"""Generate VolumePerApp's copy of the house palettes from ProjectForge's palettes.toml.

    tools/gen-palettes.py <path/to/ProjectForge/palettes.toml>

The house palettes are one list shared by every in-house app. `pf palette`
generates Kotlin/Compose, CSS and Rust copies of it; this app is Java on Views,
so it carries its own generator instead. It writes:

  app/src/main/res/values/palettes.xml        one theme per palette
  app/src/main/res/values-night/palettes.xml  the dark forms of the three
                                              palettes that follow light/dark
  app/src/main/java/com/volumeperapp/app/ui/Palettes.java
                                              the picker's list, in order

Run it again whenever `pf gate` says the palettes moved, then
`pf palette apply . --done`.

Role mapping (a KeyX role -> a Material theme attribute). It differs from the
Compose apps in one place on purpose: this app draws its channel strips as
cards on the page, so a card is the palette's `surface` (the key face) rather
than the background, and the raised colour is `colorSurfaceVariant`, which the
icon tiles and the neutral banner use.
"""
import pathlib
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/volumeperapp/app/ui/Palettes.java"

HEADER = ("Generated from the ProjectForge house palettes by tools/gen-palettes.py"
          " - do not edit here; change the palette with `pf palette`, then re-run it.")

# (theme attribute, KeyX role) for the dark/fixed form.
ROLE_MAP = [
    ("colorPrimary", "accent"),
    ("colorOnPrimary", "on_accent"),
    ("colorPrimaryContainer", "pressed"),
    ("colorOnPrimaryContainer", "text"),
    ("colorSecondary", "accent_secondary"),
    ("colorOnSecondary", "background"),
    ("colorSecondaryContainer", "pressed"),
    ("colorOnSecondaryContainer", "text"),
    ("colorTertiary", "highlight"),
    ("colorOnTertiary", "background"),
    ("colorSurface", "surface"),
    ("colorOnSurface", "text"),
    ("colorSurfaceVariant", "raised"),
    ("colorOnSurfaceVariant", "text_secondary"),
    ("colorSurfaceContainerLowest", "background"),
    ("colorSurfaceContainerLow", "bars"),
    ("colorSurfaceContainer", "bars"),
    ("colorSurfaceContainerHigh", "raised"),
    ("colorSurfaceContainerHighest", "raised"),
    ("colorOutline", "border"),
    ("colorOutlineVariant", "border"),
    ("android:colorBackground", "background"),
    ("colorOnBackground", "text"),
    ("android:windowBackground", "background"),
    ("android:statusBarColor", "background"),
    ("android:navigationBarColor", "background"),
]

# Material role names (palettes.toml `light` / `overrides`) that are not
# simply "color" + Capitalised.
SPECIAL = {"background": ["android:colorBackground", "android:windowBackground",
                          "android:statusBarColor", "android:navigationBarColor"]}


def attrs_for_material_role(role):
    if role in SPECIAL:
        return SPECIAL[role]
    return ["color" + role[0].upper() + role[1:]]


def luminance(hex_colour):
    def channel(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    h = hex_colour.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def check(hex_colour, where):
    h = hex_colour.lstrip("#")
    if len(h) != 6 or any(c not in "0123456789abcdefABCDEF" for c in h):
        sys.exit(f"{where}: {hex_colour!r} is not #RRGGBB")
    return "#" + h.upper()


def ident(name):
    return "".join(part for part in name.replace("-", " ").split())


def dark_items(p):
    roles = p["roles"]
    items = {attr: check(roles[role], f"{p['name']}.{role}") for attr, role in ROLE_MAP}
    for role, value in p.get("overrides", {}).items():
        for attr in attrs_for_material_role(role):
            items[attr] = check(value, f"{p['name']}.overrides.{role}")
    return items


def light_items(p):
    items = {}
    for role, value in p["light"].items():
        for attr in attrs_for_material_role(role):
            items[attr] = check(value, f"{p['name']}.light.{role}")
    return items


def style(name, items, light_bars, mono, indent="    "):
    out = [f'{indent}<style name="Theme.VolumePerApp.{name}">']
    for attr, value in items.items():
        out.append(f'{indent}    <item name="{attr}">{value}</item>')
    flag = "true" if light_bars else "false"
    out.append(f'{indent}    <item name="android:windowLightStatusBar">{flag}</item>')
    out.append(f'{indent}    <item name="android:windowLightNavigationBar" tools:targetApi="27">{flag}</item>')
    if mono:
        out.append(f'{indent}    <item name="vpaFontFamily">monospace</item>')
        out.append(f'{indent}    <item name="android:textViewStyle">@style/Widget.VolumePerApp.TextView.Mono</item>')
        out.append(f'{indent}    <item name="materialButtonStyle">@style/Widget.VolumePerApp.Button.Mono</item>')
    out.append(f"{indent}</style>")
    return "\n".join(out)


def material_you(night):
    flag = "false" if night else "true"
    return "\n".join([
        "    <!-- Material You: the colours come from the wallpaper at runtime"
        " (DynamicColors); below Android 12 these are stock Material 3. -->",
        '    <style name="Theme.VolumePerApp.MaterialYou" parent="Theme.VolumePerApp.MaterialYouBase">',
        f'        <item name="android:windowLightStatusBar">{flag}</item>',
        f'        <item name="android:windowLightNavigationBar" tools:targetApi="27">{flag}</item>',
        "    </style>",
    ])


def xml(body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {HEADER} -->\n"
            '<resources xmlns:tools="http://schemas.android.com/tools">\n'
            f"{body}\n</resources>\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    palettes = tomllib.loads(pathlib.Path(sys.argv[1]).read_text())["palette"]

    day, night, java = [], [], []
    for p in palettes:
        name = p["name"]
        mono = bool(p.get("mono"))
        if p.get("dynamic"):
            day.append(material_you(False))
            night.append(material_you(True))
            java.append((name, p.get("label", name), "MaterialYou", True, True, False))
            continue
        dark = dark_items(p)
        bg_dark = luminance(dark["android:colorBackground"]) < 0.5
        if "light" in p:
            # Follows the light/dark setting: light form by day, KeyX roles by night.
            day.append(style(ident(name), light_items(p), True, mono))
            night.append(style(ident(name), dark, not bg_dark, mono))
            java.append((name, p.get("label", name), ident(name), True, True, mono))
        else:
            # One look, whatever the system says; the bars follow its luminance.
            day.append(style(ident(name), dark, not bg_dark, mono))
            java.append((name, p.get("label", name), ident(name), False, bg_dark, mono))

    (RES / "values/palettes.xml").write_text(xml("\n\n".join(day)))
    (RES / "values-night/palettes.xml").write_text(xml("\n\n".join(night)))

    rows = ",\n".join(
        f'            new Palette("{n}", "{label}", R.style.Theme_VolumePerApp_{s}, '
        f'{str(follows).lower()}, {str(dark).lower()}, {str(mono).lower()})'
        for n, label, s, follows, dark, mono in java)
    JAVA.write_text(f"""// {HEADER}
package com.volumeperapp.app.ui;

import com.volumeperapp.app.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The house palettes, in the picker's order. Saved by NAME, so adding one never
 * shifts a saved choice.
 */
public final class Palettes {{

    public static final class Palette {{
        public final String name;
        public final String label;
        public final int style;
        /** True for the palettes with a light and a dark form; the rest are one look. */
        public final boolean followsMode;
        /** For a fixed palette: whether that one look is dark. */
        public final boolean dark;
        public final boolean mono;

        Palette(String name, String label, int style, boolean followsMode, boolean dark,
                boolean mono) {{
            this.name = name;
            this.label = label;
            this.style = style;
            this.followsMode = followsMode;
            this.dark = dark;
            this.mono = mono;
        }}
    }}

    public static final List<Palette> ALL = Collections.unmodifiableList(Arrays.asList(
{rows}
    ));

    private Palettes() {{
    }}
}}
""")
    print(f"{len(palettes)} palettes written")


if __name__ == "__main__":
    main()
