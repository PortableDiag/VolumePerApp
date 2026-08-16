#!/usr/bin/env python3
"""
Draws the VolumePerApp signal path: what the platform does on its own, and what
changes when a uid is routed.

Composed by hand rather than laid out by a graph engine, because the arrangement
is the argument. The two rows are the same journey — app to speaker — and the
point is made by them being vertically aligned, so the reader sees that the only
difference is a detour. An auto-layout tool cannot know that and would place the
boxes in whatever order is merely legal.

Palette is three colours with fixed jobs: blue is the audio path, orange is the
part VolumePerApp inserts, red is the failure case. Four type sizes, no more.

    python3 docs/signal-path.py [out.png]
"""

import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 1600, 1240
BG = (12, 16, 28)
INK = (233, 238, 247)
DIM = (148, 166, 192)
BLUE = (96, 165, 250)
BLUE_FILL = (22, 34, 61)
ORANGE = (251, 146, 60)
ORANGE_FILL = (46, 30, 12)
RED = (248, 113, 113)
RED_FILL = (46, 18, 18)
GREY = (55, 68, 96)

F = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FM = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"

f_title = ImageFont.truetype(FB, 40)
f_head = ImageFont.truetype(FB, 25)
f_body = ImageFont.truetype(F, 19)
f_mono = ImageFont.truetype(FM, 17)
f_small = ImageFont.truetype(F, 16)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def box(x, y, w, h, edge, fill, title, lines=(), mono=False, dash=False):
    """A labelled box. Title in the edge colour, body dim beneath it."""
    if dash:
        for i in range(0, w, 16):
            d.line([(x + i, y), (x + min(i + 9, w), y)], fill=edge, width=2)
            d.line([(x + i, y + h), (x + min(i + 9, w), y + h)], fill=edge, width=2)
        for i in range(0, h, 16):
            d.line([(x, y + i), (x, y + min(i + 9, h))], fill=edge, width=2)
            d.line([(x + w, y + i), (x + w, y + min(i + 9, h))], fill=edge, width=2)
    else:
        d.rounded_rectangle([x, y, x + w, y + h], radius=12, fill=fill, outline=edge, width=2)
    d.text((x + 18, y + 14), title, font=f_head, fill=edge)
    ty = y + 48
    for ln in lines:
        d.text((x + 18, ty), ln, font=(f_mono if mono else f_body), fill=DIM)
        ty += 25


def arrow(x1, y1, x2, y2, color, label=None, label_above=True, width=3):
    d.line([(x1, y1), (x2, y2)], fill=color, width=width)
    # Head, oriented along the segment.
    import math
    ang = math.atan2(y2 - y1, x2 - x1)
    for s in (2.6, -2.6):
        d.line([(x2, y2),
                (x2 + 16 * math.cos(ang + s), y2 + 16 * math.sin(ang + s))],
               fill=color, width=width)
    if label:
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        tw = d.textlength(label, font=f_small)
        d.text((mx - tw / 2, my - 26 if label_above else my + 10),
               label, font=f_small, fill=color)


# ----------------------------------------------------------------- title

d.text((60, 46), "Per-app volume: where the audio actually goes",
       font=f_title, fill=INK)
d.text((60, 100),
       "Android 15 / API 35, verified on device. The platform has no per-app volume; "
       "a loopback AudioMix is how one is built.",
       font=f_body, fill=DIM)

# ------------------------------------------------- row 1: without routing

Y1 = 190
d.text((60, Y1), "WITHOUT VOLUMEPERAPP   —   or with the fader at 100 %",
       font=f_head, fill=DIM)
d.text((60, Y1 + 32),
       "An app at 100 % is never routed at all: no mix, no thread, no added latency.",
       font=f_small, fill=GREY)

y = Y1 + 78
box(60, y, 330, 130, BLUE, BLUE_FILL, "Spotify  (uid N)",
    ["AudioTrack", "usage = MEDIA"])
arrow(400, y + 65, 610, y + 65, BLUE, "audioserver mixes it in")
box(620, y, 300, 130, BLUE, BLUE_FILL, "Primary output",
    ["stream volume only", "global, not per app"])
arrow(930, y + 65, 1130, y + 65, BLUE)
box(1140, y, 260, 130, BLUE, BLUE_FILL, "Speaker", ["at full level"])

# --------------------------------------------------- row 2: with routing

Y2 = 470
d.line([(60, Y2 - 26), (W - 60, Y2 - 26)], fill=GREY, width=1)
d.text((60, Y2), "WITH THE FADER MOVED   —   the uid is diverted",
       font=f_head, fill=ORANGE)
d.text((60, Y2 + 32),
       "Everything orange is VolumePerApp. The app is unchanged and unaware; "
       "only its route is different.",
       font=f_small, fill=GREY)

y = Y2 + 78
box(60, y, 330, 150, BLUE, BLUE_FILL, "Spotify  (uid N)",
    ["AudioTrack", "usage = MEDIA", "unchanged, unaware"])

arrow(400, y + 75, 560, y + 75, ORANGE, "diverted", label_above=True)

box(570, y, 420, 150, ORANGE, ORANGE_FILL, "AudioMix",
    ["rule       RULE_MATCH_UID = 4",
     "routeFlags ROUTE_FLAG_LOOP_BACK = 2",
     "format     48 kHz stereo PCM 16"], mono=True)

# The mix does NOT reach the speaker — say so where the eye expects the arrow.
d.line([(780, y + 150), (780, y + 205)], fill=RED, width=2)
d.line([(756, y + 205), (804, y + 229)], fill=RED, width=3)
d.line([(756, y + 229), (804, y + 205)], fill=RED, width=3)
d.text((826, y + 200), "loopback means it stops here.", font=f_small, fill=RED)
d.text((826, y + 222), "nothing reaches the speaker on its own.",
       font=f_small, fill=RED)

arrow(1000, y + 75, 1140, y + 75, ORANGE)
box(1150, y, 390, 150, ORANGE, ORANGE_FILL, "StreamPump",
    ["createAudioRecordSink(mix)",
     "sample x gain, clamped",
     "-> AudioTrack (our uid)"], mono=True)

# Down and back to the speaker.
arrow(1345, y + 150, 1345, y + 245, ORANGE)
box(1150, y + 255, 390, 96, BLUE, BLUE_FILL, "Speaker",
    ["at the level you set"])

# ----------------------------------------------------------- the measure

Y3 = 975
d.line([(60, Y3 - 22), (W - 60, Y3 - 22)], fill=GREY, width=1)
d.text((60, Y3), "MEASURED, NOT ASSERTED", font=f_head, fill=INK)
d.text((60, Y3 + 34),
       "A fader that moves and changes nothing looks identical to one that works, "
       "so the pump meters itself.",
       font=f_small, fill=DIM)

cols = [("fader", 60), ("0 %", 470), ("25 %", 610), ("50 %", 750),
        ("150 %", 890), ("muted", 1040)]
rows = [("peak in", ["16383", "16383", "16383", "16383", "16383"]),
        ("peak out", ["0", "4096", "8192", "24575", "0"]),
        ("measured gain", ["0.000", "0.250", "0.500", "1.500", "0.000"])]

for label, x in cols:
    d.text((x, Y3 + 70), label, font=f_mono, fill=ORANGE if x > 60 else DIM)
ry = Y3 + 100
for name, vals in rows:
    d.text((60, ry), name, font=f_mono, fill=DIM)
    for (_, x), v in zip(cols[1:], vals):
        d.text((x, ry), v, font=f_mono, fill=INK)
    ry += 26

d.text((1200, Y3 + 100),
       "440 Hz fixture at 0.5 full scale.", font=f_small, fill=DIM)
d.text((1200, Y3 + 124),
       "tools/tone/  ->  Diagnostics", font=f_mono, fill=DIM)
d.text((1200, Y3 + 150),
       "dumpsys audio agrees: routed app", font=f_small, fill=DIM)
d.text((1200, Y3 + 172),
       "on remote_submix, VPA on speaker.", font=f_small, fill=DIM)

out = sys.argv[1] if len(sys.argv) > 1 else "signal-path.png"
img.save(out)
print("wrote", out)
