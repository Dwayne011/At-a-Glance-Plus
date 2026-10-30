#!/usr/bin/env python3
"""Generates the weather card layouts: the Material You one (the default, weather spec 1) and the three
A+C hybrid ones, so their shared parts match.

Small text (forecast times and temperatures, the date and high/low lines) auto-sizes: it starts
at the spec's size and shrinks to fit its box, rather than being clipped or cut with "…" when the
phone's font size is larger than the spec assumes. Auto-sizing needs a fixed box, so those views
have a set height and fill their width. Run from the repository root; the output is committed.
"""
import os

OUT = "app/src/main/res/layout"
HDR = '<?xml version="1.0" encoding="utf-8"?>\n'


def auto(id_, max_sp, min_sp, height, colour, medium=False, width="match_parent", gravity="center", extra=""):
    fam = "sans-serif-medium" if medium else "sans-serif"
    return f'''<TextView
                android:id="@+id/{id_}"
                android:layout_width="{width}"
                android:layout_height="{height}dp"
                android:autoSizeMaxTextSize="{max_sp}sp"
                android:autoSizeMinTextSize="{min_sp}sp"
                android:autoSizeStepGranularity="0.5sp"
                android:autoSizeTextType="uniform"
                android:fontFamily="{fam}"
                android:gravity="{gravity}"
                android:includeFontPadding="false"
                android:maxLines="1"
                android:textColor="{colour}"{extra} />'''


LEFT = f'''        <!-- Shared left block: icon, temperature, date and high/low/feels, then up to 2 chips. -->
        <LinearLayout
            android:id="@+id/weather_left"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <ImageView
                    android:id="@+id/weather_icon"
                    android:layout_width="28dp"
                    android:layout_height="28dp"
                    android:contentDescription="@null"
                    android:src="@drawable/ic_weather_cloudy" />

                <TextView
                    android:id="@+id/weather_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:fontFamily="sans-serif-medium"
                    android:includeFontPadding="false"
                    android:singleLine="true"
                    android:textColor="#FFFFFFFF"
                    android:textSize="28sp" />

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="10dp"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    {auto("weather_title", 15, 10, 19, "#FFFFFFFF", medium=True, gravity="start|center_vertical")}

                    {auto("weather_detail", 11.5, 7, 15, "#FFC4C6D0", gravity="start|center_vertical")}
                </LinearLayout>
            </LinearLayout>

            <LinearLayout
                android:id="@+id/complications_anchor"
                android:layout_width="match_parent"
                android:layout_height="22dp"
                android:layout_marginTop="3dp"
                android:orientation="horizontal" />
        </LinearLayout>
'''

# Scenes look: a sky and weather layers behind the content, clipped to the card's corners. The
# still layers are images. The animated ones are all here, hidden, and code shows those needed:
# each is an indeterminate ProgressBar, which starts its animated drawable by itself (a plain
# ImageView in RemoteViews never does), with its size limits lifted so it fills the card. They
# are part of the layout rather than added as nested views, as the launcher animated only those.
ANIMATED_LAYERS = ["sun", "stars", "clouds_light", "clouds_light_night", "clouds_heavy", "clouds_heavy_night", "fog", "rain", "snow", "bolt"]
SCENE = '''
    <FrameLayout
        android:id="@+id/weather_scene"
        android:layout_width="match_parent"
        android:layout_height="76dp"
        android:layout_gravity="center_vertical"
        android:background="@drawable/card_scene_shape"
        android:clipToOutline="true"
        android:visibility="gone">

        <ImageView
            android:id="@+id/scene_sky"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@null"
            android:scaleType="fitXY" />

        <ImageView
            android:id="@+id/scene_a"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@null"
            android:scaleType="fitXY"
            android:visibility="gone" />

        <ImageView
            android:id="@+id/scene_b"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@null"
            android:scaleType="fitXY"
            android:visibility="gone" />

        <ImageView
            android:id="@+id/scene_c"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:contentDescription="@null"
            android:scaleType="fitXY"
            android:visibility="gone" />

''' + "\n".join(f'''        <ProgressBar
            android:id="@+id/scene_anim_{name}"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:indeterminate="true"
            android:indeterminateDrawable="@drawable/avd_scene_{name}"
            android:indeterminateOnly="true"
            android:maxHeight="400dp"
            android:maxWidth="2000dp"
            android:minHeight="0dp"
            android:minWidth="0dp"
            android:visibility="gone" />
''' for name in ANIMATED_LAYERS) + '''    </FrameLayout>
'''

RING = '''
    <!-- Hold ring (spec 6.3): traces the card's edge while it is held, until the launcher's menu opens. -->
    <ImageView
        android:id="@+id/hold_bar"
        android:layout_width="match_parent"
        android:layout_height="76dp"
        android:layout_gravity="center_vertical"
        android:contentDescription="@null"
        android:duplicateParentState="true"
        android:scaleType="fitXY"
        android:src="@drawable/hold_bar_amber_400" />
</FrameLayout>
'''


def card(comment, right):
    return HDR + f'''<!--
    {comment}
    Weather A+C hybrid spec: 16dp left, 14dp right and 8dp top/bottom padding (350 x 60dp inside).
    The background follows the weather, or a scene does (set in code). Generated by tools/gen_weather_layouts.py.
-->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/card_root"
    style="@style/AagCardRoot">
{SCENE}
    <LinearLayout
        android:id="@+id/card_body"
        style="@style/AagCardBody"
        android:background="@drawable/card_bg_weather_cloudy"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingTop="8dp"
        android:paddingEnd="14dp"
        android:paddingBottom="8dp">

{LEFT}
{right}
    </LinearLayout>
{RING}'''


def divider(h):
    return f'''<ImageView
                android:layout_width="1dp"
                android:layout_height="{h}dp"
                android:contentDescription="@null"
                android:scaleType="fitXY"
                android:src="@drawable/forecast_divider" />'''


def column(n):
    return f'''<LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                {auto(f"f{n}_time", 10.5, 7, 13, "#FFC4C6D0")}

                <ImageView
                    android:id="@+id/f{n}_icon"
                    android:layout_width="18dp"
                    android:layout_height="18dp"
                    android:layout_marginTop="2dp"
                    android:layout_marginBottom="2dp"
                    android:contentDescription="@null" />

                {auto(f"f{n}_temp", 12, 8, 15, "#FFFFFFFF", medium=True)}
            </LinearLayout>'''


COLUMNS = f'''        <!-- Variant 1: 3-hour columns, then 2 stacked chips. -->
        <LinearLayout
            android:id="@+id/forecast"
            android:layout_width="102dp"
            android:layout_height="60dp"
            android:layout_marginStart="6dp"
            android:layout_marginEnd="4dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            {column(1)}

            {divider(44)}

            {column(2)}

            {divider(44)}

            {column(3)}
        </LinearLayout>

        <LinearLayout
            android:id="@+id/complications"
            android:layout_width="72dp"
            android:layout_height="60dp"
            android:gravity="center"
            android:orientation="vertical" />'''


def cell(n):
    return f'''<LinearLayout
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:orientation="vertical">

                {auto(f"f{n}_time", 10, 7, 12, "#FFC4C6D0")}

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="15dp"
                    android:gravity="center"
                    android:orientation="horizontal">

                    <ImageView
                        android:id="@+id/f{n}_icon"
                        android:layout_width="13dp"
                        android:layout_height="13dp"
                        android:contentDescription="@null" />

                    {auto(f"f{n}_temp", 12, 8, 15, "#FFFFFFFF", medium=True, width="0dp", gravity="start|center_vertical", extra=chr(10) + '                    android:layout_weight="1"' + chr(10) + '                    android:layout_marginStart="3dp"')}
                </LinearLayout>
            </LinearLayout>'''


SPLIT = f'''        <!-- Variant 2: compact 3-hour row over 2 side-by-side chips. -->
        <LinearLayout
            android:layout_width="174dp"
            android:layout_height="60dp"
            android:layout_marginStart="6dp"
            android:gravity="center_vertical"
            android:orientation="vertical">

            <LinearLayout
                android:id="@+id/forecast"
                android:layout_width="match_parent"
                android:layout_height="28dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                {cell(1)}

                {divider(22)}

                {cell(2)}

                {divider(22)}

                {cell(3)}
            </LinearLayout>

            <ImageView
                android:id="@+id/forecast_rule"
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="3dp"
                android:layout_marginBottom="3dp"
                android:contentDescription="@null"
                android:scaleType="fitXY"
                android:src="@drawable/forecast_divider" />

            <LinearLayout
                android:id="@+id/complications"
                android:layout_width="match_parent"
                android:layout_height="24dp"
                android:orientation="horizontal" />
        </LinearLayout>'''


def row(n, top):
    mt = '\n                android:layout_marginTop="2dp"' if top else ''
    return f'''<LinearLayout
                android:layout_width="match_parent"
                android:layout_height="22dp"{mt}
                android:gravity="center_vertical"
                android:orientation="horizontal">

                {auto(f"f{n}_time", 10.5, 7, 14, "#FFC4C6D0", width="0dp", gravity="start|center_vertical", extra=chr(10) + '                android:layout_weight="1"')}

                <ImageView
                    android:id="@+id/f{n}_icon"
                    android:layout_width="14dp"
                    android:layout_height="14dp"
                    android:layout_marginStart="3dp"
                    android:contentDescription="@null" />

                {auto(f"f{n}_temp", 12, 8, 15, "#FFFFFFFF", medium=True, width="24dp", gravity="end|center_vertical", extra=chr(10) + '                android:layout_marginStart="3dp"')}
            </LinearLayout>'''


GRID = f'''        <!-- Variant 3: 2-hour forecast box, then a 2 x 2 chip grid. -->
        <LinearLayout
            android:id="@+id/forecast"
            android:layout_width="82dp"
            android:layout_height="54dp"
            android:layout_marginStart="6dp"
            android:layout_marginEnd="6dp"
            android:background="@drawable/forecast_box_bg"
            android:gravity="center"
            android:orientation="vertical"
            android:paddingStart="6dp"
            android:paddingEnd="6dp">

            {row(1, False)}

            {row(2, True)}
        </LinearLayout>

        <LinearLayout
            android:layout_width="96dp"
            android:layout_height="54dp"
            android:gravity="center_vertical"
            android:orientation="vertical">

            <LinearLayout
                android:id="@+id/complications"
                android:layout_width="match_parent"
                android:layout_height="27dp"
                android:orientation="horizontal" />

            <LinearLayout
                android:id="@+id/complications_2"
                android:layout_width="match_parent"
                android:layout_height="27dp"
                android:orientation="horizontal" />
        </LinearLayout>'''


def hour(n):
    """One hour of the Material You forecast (weather spec 1.3): time in its own small pill, icon, temperature.
    The columns are 34dp and the pills 32 x 15dp with 3dp inside (the spec's 32 and 30 left "02:00" touching the pill's edge)."""
    hidden = '\n            android:visibility="gone"' if n == 4 else ''
    return f'''<LinearLayout
            android:id="@+id/forecast_col_{n}"
            android:layout_width="34dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical"{hidden}>

            <TextView
                android:id="@+id/f{n}_time"
                android:layout_width="32dp"
                android:layout_height="15dp"
                android:autoSizeMaxTextSize="9.5sp"
                android:autoSizeMinTextSize="7sp"
                android:autoSizeStepGranularity="0.5sp"
                android:autoSizeTextType="uniform"
                android:background="@drawable/forecast_hour_pill"
                android:fontFamily="sans-serif-medium"
                android:gravity="center"
                android:includeFontPadding="false"
                android:maxLines="1"
                android:paddingStart="3dp"
                android:paddingEnd="3dp"
                android:textColor="#FFE2E2E6" />

            <ImageView
                android:id="@+id/f{n}_icon"
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_marginTop="5dp"
                android:layout_marginBottom="4dp"
                android:contentDescription="@null" />

            {auto(f"f{n}_temp", 12, 8, 15, "#FFFFFFFF", medium=True)}
        </LinearLayout>'''


MATERIAL = HDR + f'''<!--
    8.1 Weather, Material You layout (weather spec 1, Option 1 + A+C1 adaptive overflow), the default.
    Zone 1: icon, temperature, date (or condition) and high/low/feels, with 1 or 2 chips under them.
    Zone 2: the next 3 hours, or 4 when the right column is hidden. Zone 3: up to 2 stacked chips,
    or one stacked over both slots, shown only when the chips do not all fit under the temperature.
    The surface (gradient and wave, or a scene) is drawn in weather_scene, behind the content.
    Padding 16dp left, 14dp right, 6dp top and bottom (350 x 64dp inside). Generated by tools/gen_weather_layouts.py.
-->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/card_root"
    style="@style/AagCardRoot">
{SCENE}
    <LinearLayout
        android:id="@+id/card_body"
        style="@style/AagCardBody"
        android:background="@drawable/card_bg_material_border"
        android:baselineAligned="false"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingTop="6dp"
        android:paddingEnd="14dp"
        android:paddingBottom="6dp">

        <LinearLayout
            android:id="@+id/weather_left"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="36dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <ImageView
                    android:id="@+id/weather_icon"
                    android:layout_width="28dp"
                    android:layout_height="28dp"
                    android:contentDescription="@null"
                    android:src="@drawable/ic_weather_cloudy" />

                <TextView
                    android:id="@+id/weather_temp"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="6dp"
                    android:fontFamily="sans-serif-medium"
                    android:includeFontPadding="false"
                    android:letterSpacing="-0.03"
                    android:singleLine="true"
                    android:textColor="#FFFFFFFF"
                    android:textSize="28sp" />

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    {auto("weather_title", 15, 10, 18, "#FFFFFFFF", medium=True, gravity="start|center_vertical")}

                    {auto("weather_detail", 11.5, 7, 14, "#FFC4C6D0", gravity="start|center_vertical", extra=chr(10) + '                android:layout_marginTop="1dp"')}
                </LinearLayout>
            </LinearLayout>

            <LinearLayout
                android:id="@+id/complications_anchor"
                android:layout_width="match_parent"
                android:layout_height="26dp"
                android:layout_marginTop="2dp"
                android:gravity="center_vertical"
                android:orientation="horizontal" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/forecast"
            android:layout_width="wrap_content"
            android:layout_height="62dp"
            android:layout_marginStart="6dp"
            android:layout_marginEnd="6dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

        {hour(1)}

        {hour(2)}

        {hour(3)}

        {hour(4)}
        </LinearLayout>

        <LinearLayout
            android:id="@+id/complications"
            android:layout_width="78dp"
            android:layout_height="64dp"
            android:gravity="end|center_vertical"
            android:orientation="vertical"
            android:visibility="gone" />
    </LinearLayout>
{RING}'''

with open(os.path.join(OUT, "card_weather_material.xml"), "w") as f:
    f.write(MATERIAL)

for name, comment, right in [
    ("card_weather_columns", "8.1 Weather, hybrid variant 1: 3-hour vertical columns and 2 stacked chips.", COLUMNS),
    ("card_weather_split", "8.1 Weather, hybrid variant 2: compact 3-hour row over 2 horizontal chips.", SPLIT),
    ("card_weather_grid", "8.1 Weather, hybrid variant 3: 2-hour forecast box and a 2 x 2 chip grid.", GRID),
]:
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(card(comment, right))
