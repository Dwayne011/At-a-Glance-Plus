#!/usr/bin/env python3
"""Generates the card drawables from the design spec's exact values.

Run once; the output is committed. Icons marked (Lucide) use path data from the spec's
reference prototype, which takes them from Lucide (ISC licence).
"""
import os
import re
import sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def write(name, body):
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n' + body.strip() + "\n")


# ---------------------------------------------------------------- shapes

def shape(name, *, fill=None, start=None, end=None, stroke=None, stroke_w="1dp", radius=None, oval=False, comment=""):
    parts = []
    if start:
        # Spec: 135deg top-left to bottom-right (CSS). In Android shape XML that is 315 (TL_BR).
        parts.append(f'<gradient android:angle="315" android:startColor="{start}" android:endColor="{end}" android:type="linear" />')
    elif fill:
        parts.append(f'<solid android:color="{fill}" />')
    if stroke:
        parts.append(f'<stroke android:width="{stroke_w}" android:color="{stroke}" />')
    if radius and not oval:
        parts.append(f'<corners android:radius="{radius}" />')
    kind = "oval" if oval else "rectangle"
    c = f"<!-- {comment} -->\n" if comment else ""
    write(name, f'{c}<shape {A} android:shape="{kind}">\n    ' + "\n    ".join(parts) + "\n</shape>")


# Card backgrounds, spec section 4.1: (start, end, 1dp stroke)
CARD_BG = {
    "weather_cloudy": ("#F01B2538", "#F0121926", "#24FFFFFF"),
    "weather_needed": ("#F01E222C", "#F014171E", "#33FBBF24"),
    "live": ("#F2102624", "#F20B1A19", "#422DD4BF"),
    "media": ("#F22A1924", "#F21A1017", "#40FDA4AF"),
    "calendar": ("#E61C2236", "#E6121726", "#3893C5FD"),
    "timer": ("#F2261E12", "#F218130C", "#42FBBF24"),
    "wallet": ("#F20E223D", "#F2091629", "#4760A5FA"),
    "nav": ("#F20F291E", "#F20A1B14", "#4734D399"),
    "island": ("#FA071A12", "#FA091526", "#BF25D366"),
    "aod": ("#FF000000", "#FF000000", "#33FFFFFF"),
}
for key, (s, e, st) in CARD_BG.items():
    shape(f"card_bg_{key}", start=s, end=e, stroke=st, radius="24dp", comment=f"Card background: {key} (spec 4.1)")

# Weather spec 1.3: each forecast hour's time in a small pill.
shape("forecast_hour_pill", fill="#24FFFFFF", stroke="#33FFFFFF", radius="7.5dp", comment="Weather forecast hour pill (weather spec 1.3)", stroke_w="0.8dp")
# Weather spec 1.1: the card's soft blue edge, drawn over its surface.
shape("card_bg_material_border", fill="#00000000", stroke="#2993C5FD", radius="24dp", comment="Weather card edge (weather spec 1.1)")

# Complication chips: the always-on outline (chips spec 2.3). The tonal backgrounds come from
# tools/gen_chip_tones.py.
shape("chip_bg_aod", fill="#00000000", stroke="#FF5F6368", radius="12dp", comment="Chip: always-on display")

# Card parts (spec section 8)
shape("live_badge_bg", fill="#3814B8A6", stroke="#592DD4BF", radius="12dp", comment="8.2 app badge")
shape("live_eta_bg", fill="#2E2DD4BF", stroke="#662DD4BF", radius="14dp", comment="8.2 ETA pill")
shape("segment_on", fill="#FF2DD4BF", radius="2dp", comment="8.2 progress segment, filled")
shape("segment_off", fill="#29FFFFFF", radius="2dp", comment="8.2 progress segment, unfilled")
shape("dismiss_bg", fill="#14FFFFFF", oval=True, comment="Dismiss button")
shape("media_art_placeholder", start="#FFF43F5E", end="#FF9333EA", radius="12dp", comment="8.3 album art placeholder")
shape("media_art_border", fill="#00000000", stroke="#40FFFFFF", radius="12dp", comment="8.3 album art border")
shape("media_art_fallback", fill="#1AFFFFFF", radius="12dp", comment="8.3 no album art")
shape("media_play_bg", fill="#24FFFFFF", oval=True, comment="8.3 play/pause highlight")
shape("calendar_bar", fill="#FFFFFFFF", radius="3dp", comment="8.4 colour bar (tinted per calendar)")
shape("timer_button_bg", fill="#2EFBBF24", stroke="#66FBBF24", oval=True, comment="8.5 play/pause button")
shape("wallet_badge_bg", fill="#FF00539F", stroke="#4DFFFFFF", oval=True, comment="8.6 merchant badge")
shape("wallet_code_bg", fill="#FFFFFFFF", radius="10dp", comment="8.6 scannable code box: pure white")
shape("wallet_open_bg", fill="#3360A5FA", stroke="#7360A5FA", radius="14dp", comment="8.6 Open Wallet pill")
shape("nav_box_bg", fill="#3D10B981", stroke="#7334D399", radius="12dp", comment="8.7 manoeuvre box")
shape("weather_needed_badge", fill="#29FBBF24", oval=True, comment="8.1 location needed badge")
shape("island_reply_bg", fill="#24FFFFFF", stroke="#40FFFFFF", radius="14dp", comment="6.1 Reply pill")
shape("island_avatar_bg", fill="#FFFFFFFF", oval=True, comment="6.1 avatar (tinted per app)")
shape("island_badge_bg", fill="#FF0B1220", stroke="#FFFFFFFF", oval=True, comment="6.1 app badge")
# Always-on display replacements: outlines only, no large filled areas (spec section 5.3)
shape("aod_outline_r12", fill="#00000000", stroke="#FF5F6368", radius="12dp", comment="AOD outline")
shape("aod_outline_r14", fill="#00000000", stroke="#FF5F6368", radius="14dp", comment="AOD outline pill")
shape("aod_outline_oval", fill="#00000000", stroke="#FF5F6368", oval=True, comment="AOD outline circle")
shape("aod_segment_on", fill="#FF9AA0A6", radius="2dp", comment="AOD progress segment, filled")
shape("aod_segment_off", fill="#FF202124", radius="2dp", comment="AOD progress segment, unfilled")

write("media_track_progress", f"""
<!-- 8.3 compact 3dp track progress. -->
<layer-list {A}>
    <item android:id="@android:id/background">
        <shape android:shape="rectangle"><corners android:radius="1.5dp" /><solid android:color="#29FFFFFF" /></shape>
    </item>
    <item android:id="@android:id/progress">
        <clip>
            <shape android:shape="rectangle"><corners android:radius="1.5dp" /><solid android:color="#FFFDA4AF" /></shape>
        </clip>
    </item>
</layer-list>""")

# ---------------------------------------------------------------- icons


def rect_path(x, y, w, h, rx=0):
    x, y, w, h, rx = map(float, (x, y, w, h, rx))
    if rx <= 0:
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z"
    return (f"M{x + rx:g},{y:g}h{w - 2 * rx:g}a{rx:g},{rx:g} 0 0 1 {rx:g},{rx:g}v{h - 2 * rx:g}"
            f"a{rx:g},{rx:g} 0 0 1 {-rx:g},{rx:g}h{-(w - 2 * rx):g}a{rx:g},{rx:g} 0 0 1 {-rx:g},{-rx:g}"
            f"v{-(h - 2 * rx):g}a{rx:g},{rx:g} 0 0 1 {rx:g},{-rx:g}z")


def circle_path(cx, cy, r):
    cx, cy, r = map(float, (cx, cy, r))
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1 0 {2 * r:g},0a{r:g},{r:g} 0 1 0 {-2 * r:g},0z"


def line_path(x1, y1, x2, y2):
    return f"M{x1},{y1}L{x2},{y2}"


def vector(name, size, viewport, elements, comment=""):
    """elements: list of dicts {d, fill, stroke, width}."""
    vw, vh = viewport if isinstance(viewport, tuple) else (viewport, viewport)
    sw, sh = size if isinstance(size, tuple) else (size, size)
    paths = []
    for e in elements:
        attrs = [f'android:pathData="{e["d"]}"']
        attrs.append(f'android:fillColor="{e.get("fill") or "#00000000"}"')
        if e.get("stroke"):
            attrs += [f'android:strokeColor="{e["stroke"]}"', f'android:strokeWidth="{e.get("width", 2)}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        paths.append("    <path " + "\n        ".join(attrs) + " />")
    c = f"<!-- {comment} -->\n" if comment else ""
    write(name, f'{c}<vector {A}\n    android:width="{sw}dp"\n    android:height="{sh}dp"\n'
                f'    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">\n' + "\n".join(paths) + "\n</vector>")


def aod(elements, color="#FF9AA0A6", width=1.5):
    """Always-on display version: outline only, thin, dim."""
    return [{"d": e["d"], "fill": None, "stroke": color, "width": width} for e in elements]


W = "#FFFFFFFF"
# Weather (spec prototype SVGs, 28dp)
WEATHER = {
    "ic_weather_cloudy": [
        {"d": "M17.5,19H7a5,5 0 0 1-.5,-9.97A7,7 0 0 1 20,10.5a4.5,4.5 0 0 1-2.5,8.5Z", "fill": "#FFCFD8DC", "stroke": "#FFECEFF1", "width": 1.4}],
    "ic_weather_clear_day": [
        {"d": circle_path(12, 12, 5), "fill": "#FFFFD54F", "stroke": "#FFFDE68A", "width": 1.5},
        {"d": "M12,2v2M12,20v2M4.93,4.93l1.41,1.41M17.66,17.66l1.41,1.41M2,12h2M20,12h2M6.34,17.66l-1.41,1.41M19.07,4.93l-1.41,1.41", "stroke": "#FFFFD54F", "width": 1.8}],
    "ic_weather_rain": [
        {"d": "M17.5,15H7a4.5,4.5 0 0 1-.4,-8.98A6,6 0 0 1 18.5,7.5a3.75,3.75 0 0 1-1,7.5Z", "fill": "#FF64748B", "stroke": "#FF94A3B8", "width": 1.3},
        {"d": "M8,18l-1.5,3M12,18l-1.5,3M16,18l-1.5,3", "stroke": "#FF38BDF8", "width": 1.8}],
    "ic_weather_snow": [
        {"d": "M17.5,15H7a4.5,4.5 0 0 1-.4,-8.98A6,6 0 0 1 18.5,7.5a3.75,3.75 0 0 1-1,7.5Z", "fill": "#FF475569", "stroke": "#FFCBD5E1", "width": 1.3},
        {"d": circle_path(8, 19, 1.3) + circle_path(12, 20, 1.3) + circle_path(16, 19, 1.3), "fill": "#FFE0F2FE"}],
    "ic_weather_thunder": [
        {"d": "M17.5,14H7a4.5,4.5 0 0 1-.4,-8.98A6,6 0 0 1 18.5,6.5a3.75,3.75 0 0 1-1,7.5Z", "fill": "#FF475569", "stroke": "#FF94A3B8", "width": 1.3},
        {"d": "M13,14l-3,4h3l-1,4 4,-5h-3l1,-3z", "fill": "#FFFACC15"}],
    "ic_weather_location_off": [
        {"d": "M12,2a8,8 0 0 0-8,8c0,5.25 8,12 8,12s8,-6.75 8,-12a8,8 0 0 0-8,-8z", "stroke": "#FFFBBF24", "width": 2},
        {"d": circle_path(12, 10, 3), "stroke": "#FFFBBF24", "width": 2}],
    # Not in the spec; kept from the existing set for night and partial cloud.
    "ic_weather_clear_night": [
        {"d": "M14.5,3.2A8.8,8.8 0 1,0 20.8,15.3A7,7 0 0,1 14.5,3.2z", "fill": "#FFD0E1FD"}],
}
for name, els in WEATHER.items():
    vector(name, 28, 24, els, "Weather icon (spec 8.1)")
    vector(name + "_aod", 28, 24, aod(els), "Weather icon, always-on display")

# Complication chip icons (Lucide, via the spec), 16dp, white strokes tinted at runtime.
CHIP = {
    "mail": [rect_path(2, 4, 20, 16, 2), "M22,7l-10,7L2,7"],
    "phone": ["M22,16.92v3a2,2 0 0 1-2.18,2 19.79,19.79 0 0 1-8.63,-3.07 19.5,19.5 0 0 1-6,-6 19.79,19.79 0 0 1-3.07,-8.67A2,2 0 0 1 4.11,2h3a2,2 0 0 1 2,1.72c.12,.96 .36,1.9 .7,2.81a2,2 0 0 1-.45,2.11L8.09,9.91a16,16 0 0 0 6,6l1.27,-1.27a2,2 0 0 1 2.11,-.45c.91,.34 1.85,.58 2.81,.7A2,2 0 0 1 22,16.92z"],
    "chat": ["M21,15a2,2 0 0 1-2,2H7l-4,4V5a2,2 0 0 1 2,-2h14a2,2 0 0 1 2,2z"],
    "alarm": [circle_path(12, 13, 8), "M12,9v4l2,2", "M5,3L2,6", "M22,6l-3,-3"],
    "screen": [rect_path(5, 2, 14, 20, 2), "M12,18h.01"],
    "steps": ["M4,16v-2.38C4,11.5 2.97,10.5 3,8c.03,-2.72 1.49,-6 4.5,-6C9.37,2 10,3.8 10,5.5c0,3.11 -2,5.66 -2,8.68V16a2,2 0 1 1-4,0Z",
              "M20,20v-2.38c0,-2.12 1.03,-3.12 1,-5.62 -.03,-2.72 -1.49,-6 -4.5,-6C14.63,6 14,7.8 14,9.5c0,3.11 2,5.66 2,8.68V20a2,2 0 1 0 4,0Z"],
    "sleep": ["M3,11h8l-8,10h8", "M14,3h7l-7,8h7"],  # two Zs, like 💤
    "sunset": ["M12,10V2", "M22,22H2", "M16,18a4,4 0 0 0-8,0"],
    "battery": [rect_path(2, 7, 16, 10, 2), line_path(22, 11, 22, 13)],
    "buds": ["M3,14h3a2,2 0 0 1 2,2v3a2,2 0 0 1-2,2H5a2,2 0 0 1-2,-2v-7a9,9 0 0 1 18,0v7a2,2 0 0 1-2,2h-1a2,2 0 0 1-2,-2v-3a2,2 0 0 1 2,-2h3"],
    "countdown": [rect_path(3, 4, 18, 18, 2), line_path(3, 10, 21, 10)],
    "bell": ["M6,8a6,6 0 0 1 12,0c0,7 3,9 3,9H3s3,-2 3,-9"],
    # Health metrics.
    "heart": ["M19,14c1.49,-1.46 3,-3.21 3,-5.5A5.5,5.5 0 0 0 16.5,3c-1.76,0 -3,.5 -4.5,2 -1.5,-1.5 -2.74,-2 -4.5,-2A5.5,5.5 0 0 0 2,8.5c0,2.3 1.5,4.05 3,5.5l7,7Z"],
    "pulse": ["M22,12h-4l-3,9L9,3l-3,9H2"],
    "flame": ["M8.5,14.5A2.5,2.5 0 0 0 11,12c0,-1.38 -.5,-2 -1,-3 -1.072,-2.143 -.224,-4.054 2,-6 .5,2.5 2,4.9 4,6.5 2,1.6 3,3.5 3,5.5a7,7 0 1 1-14,0c0,-1.153 .433,-2.294 1,-3a2.5,2.5 0 0 0 2.5,2.5z"],
    "route": [circle_path(6, 19, 3), "M9,19h8.5a3.5,3.5 0 0 0 0,-7h-11a3.5,3.5 0 0 1 0,-7H15", circle_path(18, 5, 3)],
    "stairs": ["M3,20h5v-5h5v-5h5V5h3"],
    "stopwatch": [circle_path(12, 14, 8), "M12,14v-4", "M10,2h4"],
    "scale": [circle_path(12, 5, 3), "M6.5,8a2,2 0 0 0-1.905,1.46L2.1,18.5A2,2 0 0 0 4,21h16a2,2 0 0 0 1.925,-2.54L19.4,9.5A2,2 0 0 0 17.48,8Z"],
    "percent": ["M19,5L5,19", circle_path(6.5, 6.5, 2.5), circle_path(17.5, 17.5, 2.5)],
    "thermometer": ["M14,4v10.54a4,4 0 1 1-4,0V4a2,2 0 0 1 4,0Z"],
    "droplet": ["M12,22a7,7 0 0 0 7,-7c0,-2 -1,-3.9 -3,-5.5s-3.5,-4 -4,-6.5c-.5,2.5 -2,4.9 -4,6.5C6,11.1 5,13 5,15a7,7 0 0 0 7,7z"],
    "glucose": ["M12,22a7,7 0 0 0 7,-7c0,-2 -1,-3.9 -3,-5.5s-3.5,-4 -4,-6.5c-.5,2.5 -2,4.9 -4,6.5C6,11.1 5,13 5,15a7,7 0 0 0 7,7z", "M12,12v6", "M9,15h6"],
    "wind": ["M17.7,7.7a2.5,2.5 0 1 1 1.8,4.3H2", "M9.6,4.6A2,2 0 1 1 11,8H2", "M12.6,19.4A2,2 0 1 0 14,16H2"],
    "gauge": ["M12,14l4,-4", "M3.34,19a10,10 0 1 1 17.32,0"],
    "glass": ["M5,2h14l-2,20H7Z", "M6,8h12"],
    "ticket": ["M2,9a3,3 0 0 1 0,6v2a2,2 0 0 0 2,2h16a2,2 0 0 0 2,-2v-2a3,3 0 0 1 0,-6V7a2,2 0 0 0-2,-2H4a2,2 0 0 0-2,2Z"],
}
for name, ds in CHIP.items():
    els = [{"d": d, "stroke": W, "width": 2} for d in ds]
    vector(f"ic_comp_{name}", 16, 24, els, "Complication icon (Lucide via spec 7.4)")
    vector(f"ic_comp_{name}_aod", 16, 24, aod(els), "Complication icon, always-on display")

# Media, navigation, dismiss, music note
FILLED = {
    "ic_media_prev": (20, ["M6,6h2v12H6z", "M9.5,12l8.5,6V6z"]),
    "ic_media_play": (22, ["M8,5v14l11,-7z"]),
    "ic_media_pause": (22, ["M6,19h4V5H6v14z", "M14,5v14h4V5h-4z"]),
    "ic_media_next": (20, ["M6,18l8.5,-6L6,6v12z", "M16,6v12h2V6h-2z"]),
}
for name, (size, ds) in FILLED.items():
    els = [{"d": d, "fill": W} for d in ds]
    vector(name, size, 24, els, "Media control (spec 8.3)")
    vector(name + "_aod", size, 24, aod(els), "Media control, always-on display")
STROKED = {
    "ic_nav_turn_left": (24, 2.5, ["M9,14L4,9l5,-5", "M20,20v-7a4,4 0 0 0-4,-4H4"]),
    "ic_nav_walk": (24, 2.2, [circle_path(12, 5, 2), "M9,20l3,-6 3,6", "M6,8l6,2 6,-2", "M12,10v4"]),
    "ic_nav_flag": (24, 2.2, ["M4,15s1,-1 4,-1 5,2 8,2 4,-1 4,-1V3s-1,1 -4,1 -5,-2 -8,-2 -4,1 -4,1z", line_path(4, 22, 4, 15)]),
    "ic_dismiss": (12, 2.5, ["M6,6l12,12", "M18,6L6,18"]),
    "ic_music_note": (22, 2, ["M9,18V5l12,-2v13", circle_path(6, 18, 3), circle_path(18, 16, 3)]),
    "ic_chat_badge": (10, 2.5, ["M21,15a2,2 0 0 1-2,2H7l-4,4V5a2,2 0 0 1 2,-2h14a2,2 0 0 1 2,2z"]),
}
for name, (size, width, ds) in STROKED.items():
    els = [{"d": d, "stroke": W, "width": width} for d in ds]
    vector(name, size, 24, els)
    vector(name + "_aod", size, 24, aod(els))

# Wallet sample codes (spec prototype). The QR is a placeholder pattern, not a scannable code.
bars = [(0, 3), (6, 2), (11, 4), (18, 2), (24, 5), (33, 2), (39, 4), (47, 2), (53, 4), (61, 2), (67, 4), (75, 3), (82, 5), (91, 2), (97, 4)]
vector("wallet_sample_barcode", (106, 32), (106, 32), [{"d": "".join(rect_path(x, 0, w, 32) for x, w in bars), "fill": "#FF000000"}],
       "8.6 sample 1D barcode (preview only)")
vector("wallet_sample_qr", 46, 24, [{"d": "M3,3h7v7H3V3zm2,2v3h3V5H5zm9,-2h7v7h-7V3zm2,2v3h3V5h-3zM3,14h7v7H3v-7zm2,2v3h3v-3H5zm9,-2h3v2h-3v-2zm4,0h3v3h-2v-1h-1v-2zm-4,4h2v3h-2v-3zm3,1h4v2h-4v-2z", "fill": "#FF000000"}],
       "8.6 sample QR pattern (preview only; not scannable)")

# ---------------------------------------------------------------- equalisers (spec 6.2)
# Hosted in an indeterminate ProgressBar, which starts Animatable drawables by itself;
# an AnimatedVectorDrawable in a plain ImageView would never start inside RemoteViews.

DUR = [520, 680, 440, 760, 590, 480, 640, 510, 720, 460, 610, 550, 490, 670]
DELAY = [50, 180, 0, 250, 120, 300, 80, 210, 140, 30, 270, 100, 190, 60]


def bar(x, w, top, h):
    r = w / 2
    return (f"M{x + r:g},{top:g}a{r:g},{r:g} 0 0 1 {r:g},{r:g}v{h - 2 * r:g}a{r:g},{r:g} 0 0 1 {-r:g},{r:g}"
            f"a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}v{-(h - 2 * r):g}a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z")


def equaliser(name, count, bar_w, gap, colour, height=20, max_h=18, min_h=3, comment=""):
    width = count * bar_w + (count - 1) * gap
    vw = round(width + 0.5)
    rest = min_h / max_h
    groups, targets, flat = [], [], []
    top = (height - max_h) / 2
    for i in range(count):
        x = i * (bar_w + gap)
        groups.append(f'            <group android:name="b{i}" android:pivotX="{x + bar_w / 2:g}" android:pivotY="{height / 2:g}" android:scaleY="{rest:.4f}">\n'
                      f'                <path android:fillColor="{colour}" android:pathData="{bar(x, bar_w, top, max_h)}" />\n            </group>')
        targets.append(f'''    <target android:name="b{i}">
        <aapt:attr name="android:animation">
            <objectAnimator
                android:propertyName="scaleY"
                android:valueFrom="{rest:.4f}"
                android:valueTo="1"
                android:duration="{DUR[i % len(DUR)]}"
                android:startOffset="{DELAY[i % len(DELAY)]}"
                android:repeatCount="infinite"
                android:repeatMode="reverse"
                android:interpolator="@android:interpolator/accelerate_decelerate" />
        </aapt:attr>
    </target>''')
        flat.append(bar(x, bar_w, (height - min_h) / 2, min_h))
    write("avd_" + name, f"""<!-- {comment} Playing: bars bounce between {min_h}dp and {max_h}dp. -->
<animated-vector {A}
    {AAPT}>
    <aapt:attr name="android:drawable">
        <vector
            android:width="{vw}dp"
            android:height="{height}dp"
            android:viewportWidth="{vw}"
            android:viewportHeight="{height}">
{chr(10).join(groups)}
        </vector>
    </aapt:attr>
{chr(10).join(targets)}
</animated-vector>""")
    vector("ic_" + name + "_paused", (vw, height), (vw, height), [{"d": "".join(flat), "fill": colour}],
           f"{comment} Paused or always-on display: bars flat at {min_h}dp.")


equaliser("media_equalizer", 14, 3, 2.5, "#FFFDA4AF", comment="8.3 14-bar audio wave.")
equaliser("island_equalizer", 5, 2.5, 2, "#FFFFFFFF", height=14, max_h=12, min_h=3, comment="6.1 5-bar mini equaliser (tinted per app).")
print("ok", len(os.listdir(OUT)), "files in", OUT)
