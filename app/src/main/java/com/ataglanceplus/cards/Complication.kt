package com.ataglanceplus.cards

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ataglanceplus.Prefs
import com.ataglanceplus.R
import com.ataglanceplus.complications.ChipValues
import com.ataglanceplus.ui.MainActivity
import com.ataglanceplus.ui.UnlockGateActivity

/**
 * The complication catalogue (spec 7.4). [sample] is the spec's sample value, used only by the
 * rail-ticket chip on the navigation preview; every other chip shows live data.
 */
enum class Complication(
    val id: String,
    val label: String,
    val sample: String,
    @DrawableRes val icon: Int,
    @DrawableRes val aodIcon: Int,
    /** Offered in the picker; false for chips that only appear through a rule (the rail ticket). */
    val pickable: Boolean = true,
    /** Needs an account or an app chosen when it is added (Gmail, app notifications). */
    val needsArg: Boolean = false,
    /** Has more to say than fits one slot, so can be made wide or tall. */
    val canGrow: Boolean = false,
) {
    GMAIL("gmail", "Gmail unread", "4", R.drawable.ic_comp_mail, R.drawable.ic_comp_mail_aod, needsArg = true),
    MISSED_CALLS("calls", "Missed calls", "2", R.drawable.ic_comp_phone, R.drawable.ic_comp_phone_aod),
    TEXTS("texts", "Unread texts", "3", R.drawable.ic_comp_chat, R.drawable.ic_comp_chat_aod),
    ALARM("alarm", "Next alarm", "07:30", R.drawable.ic_comp_alarm, R.drawable.ic_comp_alarm_aod),
    SCREEN_TIME("screen", "Screen time", "3h 12m", R.drawable.ic_comp_screen, R.drawable.ic_comp_screen_aod, canGrow = true),
    STEPS("steps", "Steps today", "6,214", R.drawable.ic_comp_steps, R.drawable.ic_comp_steps_aod, canGrow = true),
    SLEEP("sleep", "Sleep last night", "7h 40m", R.drawable.ic_comp_sleep, R.drawable.ic_comp_sleep_aod, canGrow = true),
    SUNSET("sunset", "Next sunrise or sunset", "16:42", R.drawable.ic_comp_sunset, R.drawable.ic_comp_sunset_aod, canGrow = true),
    BATTERY("battery", "Phone battery", "67%", R.drawable.ic_comp_battery, R.drawable.ic_comp_battery_aod),
    BUDS("buds", "Bluetooth battery", "100%", R.drawable.ic_comp_bluetooth, R.drawable.ic_comp_bluetooth_aod, canGrow = true),
    COUNTDOWN("count", "Date countdown", "12d", R.drawable.ic_comp_countdown, R.drawable.ic_comp_countdown_aod, canGrow = true),
    HEALTH("health", "Health metric", "72", R.drawable.ic_comp_heart, R.drawable.ic_comp_heart_aod, needsArg = true, canGrow = true),
    ETORO("etoro", "eToro portfolio", "+$412 · +6.3%", R.drawable.ic_comp_trend, R.drawable.ic_comp_trend_aod, canGrow = true),
    APP_NOTIFICATIONS("notif", "App notifications", "5", R.drawable.ic_comp_bell, R.drawable.ic_comp_bell_aod, needsArg = true),
    RAIL_TICKET("ticket", "Rail ticket", "Ticket", R.drawable.ic_comp_ticket, R.drawable.ic_comp_ticket_aod, pickable = false);

    companion object {
        fun fromId(id: String): Complication? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How much a chip shows: one slot, or extended over two, which is two across where chips sit
 * side by side and two stacked where they are stacked.
 */
enum class ChipSize(val label: String) { STANDARD("Standard"), EXTENDED("Extended") }

/**
 * One chip as the user added it: a kind plus, for Gmail, the account, or for app notifications,
 * the app's package. Stored as "kind" or "kind:arg".
 */
data class ChipSpec(val kind: Complication, val arg: String? = null) {

    val key: String get() = if (arg == null) kind.id else "${kind.id}:$arg"

    /** Gmail and app-notification chips added before they took an account or app show nothing. */
    val configured: Boolean get() = !kind.needsArg || !arg.isNullOrEmpty()

    /** The app a tap opens when the user has not chosen one. */
    fun defaultPackage(): String? = when (kind) {
        Complication.GMAIL -> GMAIL_PKG
        Complication.MISSED_CALLS -> "com.google.android.dialer"
        Complication.TEXTS -> "com.google.android.apps.messaging"
        Complication.SCREEN_TIME -> "com.google.android.apps.wellbeing"
        Complication.SUNSET -> "com.google.android.apps.weather"
        Complication.APP_NOTIFICATIONS -> arg
        Complication.RAIL_TICKET -> "com.google.android.apps.walletnfcrel"
        Complication.ETORO -> ETORO_PKG
        else -> null
    }

    /** What a tap opens: the app the user chose, else this chip's usual screen. */
    private fun targetIntent(context: Context): Intent {
        val pm = context.packageManager
        val chosen = Prefs.chipApp(context, key)?.let { pm.getLaunchIntentForPackage(it) }
        val intent = chosen ?: defaultIntent(context) ?: Intent(context, MainActivity::class.java)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** This chip's usual screen, when the user has not chosen an app. */
    private fun defaultIntent(context: Context): Intent? = when (kind) {
        Complication.ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        // This app's own dashboards, which link on to Health Connect and Digital Wellbeing.
        Complication.STEPS -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_STEPS)
        Complication.SLEEP -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_SLEEP)
        Complication.SCREEN_TIME -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_SCREEN)
        Complication.GMAIL -> arg?.let { gmailAccount(context, it) } ?: defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
        Complication.HEALTH -> arg?.let { com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_HEALTH_PREFIX + it) }
        Complication.ETORO -> com.ataglanceplus.ui.DashboardActivity.intent(context, com.ataglanceplus.ui.DashboardActivity.KIND_ETORO)
        Complication.SUNSET -> weatherSunScreen(context) ?: defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
        Complication.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        Complication.BUDS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        else -> defaultPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
    }

    /**
     * The name of the app a tap opens when none is chosen, for settings: "Clock", "Health
     * Connect". Null when Android would ask which app to use, or nothing handles it.
     */
    fun defaultAppName(context: Context): String? {
        val pm = context.packageManager
        val intent = defaultIntent(context) ?: return null
        val info = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // The "open with" chooser belongs to Android itself.
        if (info.packageName == "android") return null
        if (info.packageName == context.packageName) return "its dashboard in this app"
        val activity = info.loadLabel(pm).toString()
        val app = info.applicationInfo.loadLabel(pm).toString()
        // Settings screens name the screen ("Bluetooth"); apps are better named by the app.
        return if (info.packageName == "com.android.settings" && activity.isNotBlank()) "Settings › $activity" else app
    }

    fun tapIntent(context: Context): PendingIntent {
        // Request codes must differ per chip so the PendingIntents do not replace each other.
        val code = REQUEST_BASE + (key.hashCode() and 0xFFFF)
        val target = PendingIntent.getActivity(
            context, code, targetIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return UnlockGateActivity.wrap(context, target, code)
    }

    companion object {
        private const val REQUEST_BASE = 0x10000
        const val GMAIL_PKG = "com.google.android.gm"
        const val ETORO_PKG = "com.etoro.openbook"

        fun parse(key: String): ChipSpec? {
            val kind = Complication.fromId(key.substringBefore(':')) ?: return null
            return ChipSpec(kind, key.substringAfter(':', "").ifEmpty { null })
        }
    }
}

/**
 * Digital Wellbeing's activity details (the screen-time chart), when this version has that
 * screen and lets other apps open it; otherwise null.
 */
fun wellbeingDashboard(context: Context): Intent? = openableScreen(context, "com.google.android.apps.wellbeing", "dashboard")

/** Pixel Weather's sunrise and sunset screen, when it has one other apps may open; otherwise null. */
fun weatherSunScreen(context: Context): Intent? = openableScreen(context, "com.google.android.apps.weather", "sun")

/**
 * A screen of [pkg] whose name contains [word], if the app lets other apps open it. These apps
 * have no documented way in to one screen, so it is looked up by name; null when there is none.
 */
private fun openableScreen(context: Context, pkg: String, word: String): Intent? {
    val activities = runCatching {
        context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_ACTIVITIES.toLong())).activities
    }.getOrNull() ?: return null
    val screen = activities.firstOrNull { it.exported && it.enabled && word in it.name.substringAfterLast('.').lowercase() } ?: return null
    // A fresh task, so back leaves the app rather than going through its earlier pages.
    return Intent().setClassName(pkg, screen.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/**
 * The Gmail app at [account]'s inbox, through the account's Gmail web link, which the app may
 * take; null when Gmail does not handle it (it then opens as usual). Gmail has no documented way
 * in to one account, so this is a best try.
 */
fun gmailAccount(context: Context, account: String): Intent? {
    val link = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mail.google.com/mail/u/?authuser=" + android.net.Uri.encode(account)))
        .setPackage(ChipSpec.GMAIL_PKG)
    return link.takeIf { it.resolveActivity(context.packageManager) != null }
}

/** Where a card's complications sit (spec 7.1). */
enum class ComplicationLayout { RIGHT, BOTTOM, NONE }

/**
 * The weather card's arrangement: Material You (weather spec 1, the default: chips under the
 * temperature, 3 or 4 hours, and a right column only when needed), or one of the A+C hybrid
 * spec's: 3-hour columns + 2 stacked chips, a compact 3-hour row over 2 wide chips, a 2-hour box +
 * 2 x 2 chip grid, or no chips (forecast only).
 */
enum class WeatherLayout(val label: String) {
    MATERIAL("Material You"),
    COLUMNS("Columns"),
    SPLIT("Split"),
    GRID("Grid"),
    NONE("No chips");

    /** The matching chip arrangement for the other cards. */
    val others: ComplicationLayout
        get() = when (this) {
            SPLIT -> ComplicationLayout.BOTTOM
            NONE -> ComplicationLayout.NONE
            else -> ComplicationLayout.RIGHT
        }
}

/** Adds complication chips to a card's `complications` container. */
object Chips {

    const val MAX_RIGHT = 2
    const val MAX_BOTTOM = 4
    /** Chips the user can pick (no practical limit). Only those with something to report are shown, up to the layout's room. */
    const val MAX_CHOSEN = 50

    /**
     * A chip shape: its layout and the sizes inside the pill, used to check that a chip's text
     * fits. [maxPillDp] is the most room the pill has (null when the row decides); [maxTextDp]
     * mirrors the text's maxWidth in the layout. The rest describe the spot, for chips taking 2
     * slots: whether chips are [stack]ed (else side by side), the tap zone ([zoneWDp], null when
     * it fits the pill) and pill sizes, and the pill's [background].
     */
    data class ChipStyle(
        val layout: Int,
        val textSp: Float,
        val iconDp: Int,
        val padDp: Int,
        val maxPillDp: Int?,
        val maxTextDp: Int,
        /** Space after the zone, for working out how many fit in a row. */
        val gapDp: Int = 6,
        val stack: Boolean = false,
        val zoneWDp: Int? = null,
        val zoneHDp: Int = 24,
        val pillHDp: Int = 24,
        /** Widest a wide pill may grow where the zone fits the pill. */
        val wideCapDp: Int = 0,
        /** A stacked 2-slot pill's height, when set rather than following from the zone. */
        val stackPillHDp: Int? = null,
    ) {
        /** The pill's natural width for [text] with its icon, in dp. */
        fun widthDp(context: Context, text: String): Float = 2 * padDp + iconDp + 4 + textWidthDp(context, text, textSp).coerceAtMost(maxTextDp.toFloat())

        /** A 2-slot chip's zone: twice as wide across a row, twice as tall in a stack. */
        val bigZoneWDp: Int? get() = if (stack) zoneWDp else zoneWDp?.let { 2 * it + gapDp }
        val bigZoneHDp: Int get() = if (stack) 2 * zoneHDp else zoneHDp
        val bigPillHDp: Int get() = if (stack) stackPillHDp ?: (2 * zoneHDp - (zoneHDp - pillHDp)) else pillHDp

        /** Room inside a 2-slot pill, in dp. */
        val bigInnerDp: Int get() = (bigZoneWDp ?: wideCapDp) - 2 * padDp

        /** A wide pill's natural width for [value], in dp (side-by-side devices for Bluetooth). */
        fun bigWidthDp(context: Context, value: ChipValues.Value): Float {
            val inner = if (value.parts.size > 1) {
                value.parts.sumOf { (16 + 4 + textWidthDp(context, it.named, 12.5f)).toDouble() }.toFloat() + PART_GAP_DP * (value.parts.size - 1)
            } else {
                iconDp + 4 + textWidthDp(context, value.wide ?: value.text, textSp)
            }
            return (2 * padDp + inner).coerceAtMost((bigZoneWDp ?: wideCapDp).toFloat())
        }
    }

    // Chips spec 2.1: a standard pill is 26dp tall with a 15dp icon, 9dp in from its ends.
    val DEFAULT_RIGHT = ChipStyle(
        R.layout.include_complication_chip, 12.5f, 15, 9, 84, 44,
        gapDp = 0, stack = true, zoneWDp = 84, zoneHDp = 38, pillHDp = 26,
    )
    val DEFAULT_STRIP = ChipStyle(
        R.layout.include_complication_chip_strip, 12.5f, 15, 9, 84, 44,
        zoneHDp = 34, pillHDp = 26, wideCapDp = 176,
    )
    val ANCHOR = ChipStyle(
        R.layout.include_chip_anchor, 11.5f, 13, 8, null, 56,
        zoneHDp = 22, pillHDp = 22, wideCapDp = 200,
    )
    /** The Material You weather card's strip under the temperature (weather spec 1.2). */
    val BOTTOM = ChipStyle(
        R.layout.include_chip_bottom, 12f, 14, 8, null, 64,
        zoneHDp = 26, pillHDp = 24, wideCapDp = 132,
    )
    /** Its right column (weather spec 1.4): 2 pills, or one stacked over both (78 x 54dp, chips spec 2.1). */
    val RIGHT_COLUMN = ChipStyle(
        R.layout.include_chip_rightcol, 12.5f, 14, 8, 78, 50,
        gapDp = 0, stack = true, zoneWDp = 78, zoneHDp = 32, pillHDp = 26, stackPillHDp = 54,
    )
    val COLUMN = ChipStyle(R.layout.include_chip_column, 12f, 14, 6, 72, 56, gapDp = 0, stack = true, zoneWDp = 72, zoneHDp = 30)
    val WIDE = ChipStyle(R.layout.include_chip_wide, 12f, 14, 6, 84, 56, zoneWDp = 84)
    val GRID = ChipStyle(R.layout.include_chip_grid, 11f, 13, 5, 48, 56, gapDp = 0, zoneWDp = 48, zoneHDp = 27)

    /** Room kept spare when choosing text for the extra chips card, whose real width is estimated. */
    private const val TEXT_SPARE_DP = 12

    /** Space between devices on a 2-slot Bluetooth chip. */
    private const val PART_GAP_DP = 8

    /** The width of [text] in dp at [sp], in the chip font, at the phone's current text size. */
    fun textWidthDp(context: Context, text: String, sp: Float): Float {
        val metrics = context.resources.displayMetrics
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        return paint.measureText(text) / metrics.density
    }

    /**
     * A chip placed in a spot, and whether it takes 2 slots there. [roomDp], when set, is the
     * most its pill may take there (a chip squeezed into the end of a row); its text shrinks to fit.
     */
    data class Placed(val spec: ChipSpec, val value: ChipValues.Value, val big: Boolean, val roomDp: Float? = null)

    /**
     * Whether [spec] takes 2 slots: an extended chip (or any chip that can be, when [always], as on
     * the extra chips card). It is wide where chips sit side by side and tall where they are
     * stacked; [style] decides which when it is drawn.
     */
    fun grows(context: Context, spec: ChipSpec, always: Boolean = false): Boolean =
        spec.kind.canGrow && (always || Prefs.chipSize(context, spec.key) == ChipSize.EXTENDED)

    /**
     * The chips of [specs] with something to show that a spot of [slots] slots takes, in order.
     * An extended chip is never shrunk: with only 1 slot left it waits for the next spot.
     */
    fun plan(context: Context, specs: List<ChipSpec>, slots: Int, style: ChipStyle, always: Boolean = false): List<Placed> {
        val placed = mutableListOf<Placed>()
        var left = slots
        for ((spec, value) in withValues(specs)) {
            if (left <= 0) break
            val big = grows(context, spec, always)
            if (big && left < 2) break
            left -= if (big) 2 else 1
            placed += Placed(spec, value, big)
        }
        return placed
    }

    /**
     * The extra chips card's two rows from [specs] (all of which it takes): every chip that can be
     * is extended, and the chips are shared between both rows rather than filling the first.
     */
    fun overflowRows(context: Context, specs: List<ChipSpec>): Pair<List<Placed>, List<Placed>> {
        val all = plan(context, specs, 2 * MAX_BOTTOM, DEFAULT_STRIP, always = true)
        fun cost(row: List<Placed>) = row.sumOf { if (it.big) 2 else 1 }
        // The most even split whose rows both fit; failing that, fill the first row.
        for (split in (all.size + 1) / 2 until all.size) {
            val first = all.take(split)
            val second = all.drop(split)
            if (cost(first) <= MAX_BOTTOM && cost(second) <= MAX_BOTTOM) return first to second
        }
        val first = plan(context, specs, MAX_BOTTOM, DEFAULT_STRIP, always = true)
        return first to all.drop(first.size)
    }

    /** How many of [specs] one extra chips card takes. */
    fun overflowTaken(context: Context, specs: List<ChipSpec>): Int =
        plan(context, specs, 2 * MAX_BOTTOM, DEFAULT_STRIP, always = true).size

    /**
     * Adds the chips that have something to show, up to the layout's maximum. A chip with no
     * value (nothing to report, no permission yet, or not set up) is left out rather than drawn
     * as "0" (spec 7.2). With none left, the container is hidden so the card's text gets the
     * whole width (spec 7.1 collapse rule).
     */
    fun add(
        context: Context,
        views: RemoteViews,
        specs: List<ChipSpec>,
        layout: ComplicationLayout,
        aod: Boolean,
        container: Int = R.id.complications,
    ) {
        if (layout == ComplicationLayout.NONE) {
            views.setViewVisibility(container, View.GONE)
            return
        }
        val bottom = layout == ComplicationLayout.BOTTOM
        addStyled(context, views, specs, if (bottom) MAX_BOTTOM else MAX_RIGHT, if (bottom) DEFAULT_STRIP else DEFAULT_RIGHT, aod, container)
    }

    /** How many of [specs] a card's chip spot takes (see [add]); 2-slot chips count once. */
    fun taken(context: Context, specs: List<ChipSpec>, layout: ComplicationLayout): Int = when (layout) {
        ComplicationLayout.NONE -> 0
        ComplicationLayout.BOTTOM -> plan(context, specs, MAX_BOTTOM, DEFAULT_STRIP).size
        ComplicationLayout.RIGHT -> plan(context, specs, MAX_RIGHT, DEFAULT_RIGHT).size
    }

    /**
     * Adds chips with something to show into [container], in [style], filling [slots] slots
     * (see [plan]). Returns how many were added; with none, the container is hidden.
     */
    fun addStyled(
        context: Context,
        views: RemoteViews,
        specs: List<ChipSpec>,
        slots: Int,
        style: ChipStyle,
        aod: Boolean,
        container: Int,
    ): Int = plan(context, specs, slots, style).also { addPlaced(context, views, it, style, aod, container) }.size

    /**
     * Adds already placed chips. Each normal chip's text is measured at the phone's text size;
     * when it does not fit the pill, the text shrinks a little (after losing any spaces), then the
     * icon is dropped, then the text shrinks the rest of the way (to 8sp at least).
     */
    fun addPlaced(context: Context, views: RemoteViews, placed: List<Placed>, style: ChipStyle, aod: Boolean, container: Int) {
        views.setViewVisibility(container, if (placed.isEmpty()) View.GONE else View.VISIBLE)
        placed.forEach { (spec, value, big, room) ->
            val child = if (big) bigChip(context, style, spec, value, aod) else normalChip(context, style, spec, value, aod, room)
            child.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
            views.addView(container, child)
        }
    }

    private fun normalChip(context: Context, style: ChipStyle, spec: ChipSpec, value: ChipValues.Value, aod: Boolean, roomDp: Float? = null): RemoteViews {
        val child = RemoteViews(context.packageName, style.layout)
        if (value.parts.size > 1) {
            // Several values (Bluetooth devices): take turns inside the pill.
            val tone = ChipTones.of(spec, value)
            child.setViewVisibility(R.id.chip_icon, View.GONE)
            child.setViewVisibility(R.id.chip_text, View.GONE)
            val flipper = RemoteViews(context.packageName, R.layout.include_chip_flipper_inner)
            value.parts.forEach { part -> flipper.addView(R.id.chip_flipper_inner, partView(context, part, aod, tone = tone)) }
            child.addView(R.id.chip_pill, flipper)
            child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + value.parts.joinToString(", ") { it.text })
            child.setInt(R.id.chip_pill, "setBackgroundResource", if (aod) R.drawable.chip_bg_aod else tone.pill)
        } else {
            // A shorter form, when there is one, rather than shrinking the text past reading.
            val room = (roomDp ?: style.maxPillDp?.toFloat())?.let { it - 2 * style.padDp - style.iconDp - 4 } ?: Float.MAX_VALUE
            val text = value.compact?.takeIf { textWidthDp(context, value.text, style.textSp) > minOf(room, style.maxTextDp.toFloat()) } ?: value.text
            child.setTextViewText(R.id.chip_text, text)
            child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: ${value.text}")
            look(child, spec, value, aod)
            fit(context, child, style, text, roomDp)
        }
        return child
    }

    /**
     * A chip taking 2 slots (chips spec 2.1). Across: one line with the whole story ("2,047 of
     * 8,000"), or every Bluetooth device side by side, and a 2dp progress line under it where the
     * chip has one. Stacked: a rounded card with the value beside the icon and up to two lines
     * under it, then the progress line; a Bluetooth chip shows the device and its level.
     */
    private fun bigChip(context: Context, style: ChipStyle, spec: ChipSpec, value: ChipValues.Value, aod: Boolean): RemoteViews {
        val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
        val sp = android.util.TypedValue.COMPLEX_UNIT_SP
        val px = android.util.TypedValue.COMPLEX_UNIT_PX
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT.toFloat()
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat()
        val tall = style.stack
        val tone = ChipTones.of(spec, value)
        val child = RemoteViews(context.packageName, R.layout.include_chip_big)

        val zoneW = style.bigZoneWDp
        if (zoneW != null) child.setViewLayoutWidth(R.id.chip_zone, zoneW.toFloat(), dip) else child.setViewLayoutWidth(R.id.chip_zone, wrap, px)
        child.setViewLayoutHeight(R.id.chip_zone, style.bigZoneHDp.toFloat(), dip)
        if (!tall && style.gapDp > 0) child.setViewLayoutMargin(R.id.chip_zone, RemoteViews.MARGIN_END, style.gapDp.toFloat(), dip)
        if (zoneW != null) child.setViewLayoutWidth(R.id.chip_pill, match, px)
        // A progress line makes an across pill 2dp taller, where its spot has the room.
        val progress = value.progress?.takeIf { !aod && (tall || style.bigZoneHDp >= style.bigPillHDp + PROGRESS_EXTRA_DP) }
        child.setViewLayoutHeight(R.id.chip_pill, (style.bigPillHDp + if (progress != null && !tall) PROGRESS_EXTRA_DP else 0).toFloat(), dip)
        val pad = if (tall) STACK_PAD_DP else ACROSS_PAD_DP
        val padV = if (tall) STACK_PAD_V_DP else 0
        child.setViewPadding(R.id.chip_pill, dp(context, pad), dp(context, padV), dp(context, pad), dp(context, padV))
        val iconDp = if (tall) STACK_ICON_DP else style.iconDp
        child.setViewLayoutWidth(R.id.chip_icon, iconDp.toFloat(), dip)
        child.setViewLayoutHeight(R.id.chip_icon, iconDp.toFloat(), dip)
        child.setTextViewTextSize(R.id.chip_text, sp, if (tall) STACK_TEXT_SP else style.textSp)
        if (!tall) child.setViewLayoutMargin(R.id.chip_text, RemoteViews.MARGIN_START, ACROSS_GAP_DP.toFloat(), dip)
        look(child, spec, value, aod, stacked = tall)
        val inner = ((if (tall) style.zoneWDp else zoneW) ?: style.wideCapDp) - 2f * pad
        progress?.let {
            child.setViewVisibility(R.id.chip_progress, View.VISIBLE)
            child.setProgressBar(R.id.chip_progress, 1000, (it * 1000).toInt().coerceIn(0, 1000), false)
        }

        if (spec.kind == Complication.BUDS && (value.parts.size > 1 || tall)) {
            child.setViewVisibility(R.id.chip_icon, View.GONE)
            child.setViewVisibility(R.id.chip_text, View.GONE)
            if (tall && value.parts.size == 1) {
                // One device: its name beside its icon, then its level beside a battery.
                val part = value.parts.first()
                val name = part.name ?: part.text
                child.addView(R.id.chip_row, partView(context, part.copy(text = name, name = null), aod, tone = tone, roomDp = inner - 16 - 4))
                child.setViewVisibility(R.id.chip_row2, View.VISIBLE)
                child.addView(R.id.chip_row2, partView(context, ChipValues.Part(R.drawable.ic_comp_battery, R.drawable.ic_comp_battery_aod, part.text), aod, tone = tone))
            } else if (tall) {
                // Two devices: each with its level, one per row. Names do not fit; any more are left off.
                value.parts.take(2).forEachIndexed { i, part ->
                    if (i == 1) child.setViewVisibility(R.id.chip_row2, View.VISIBLE)
                    child.addView(if (i == 0) R.id.chip_row else R.id.chip_row2, partView(context, part, aod, tone = tone))
                }
            } else {
                // Across: devices side by side while they fit; any more are left off.
                var used = 0f
                for ((i, part) in value.parts.withIndex()) {
                    val width = 16 + 4 + textWidthDp(context, part.named, 12.5f) + if (i > 0) PART_GAP_DP else 0
                    if (i > 0 && used + width > inner) break
                    used += width
                    child.addView(R.id.chip_row, partView(context, part, aod, gap = i > 0, named = true, tone = tone))
                }
            }
            child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + value.parts.joinToString(", ") { it.named })
            return child
        }

        if (!tall) {
            val text = value.wide ?: value.text
            child.setTextViewText(R.id.chip_text, text)
            // Shrink to fit the 2-slot pill (to 9sp at least); anything longer ends in "…" inside it.
            shrink(context, child, R.id.chip_text, text, style.textSp, inner - iconDp - ACROSS_GAP_DP, 9f)
            child.setInt(R.id.chip_text, "setMaxWidth", dp(context, (inner - iconDp - ACROSS_GAP_DP).toInt()))
            child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: $text")
            return child
        }

        val lines = value.lines
        val secondary = if (aod) Style.AOD_TEXT else tone.secondary
        val primary = if (aod) Style.AOD_TEXT else tone.text
        if (spec.kind == Complication.SUNSET) {
            // Both sun times, the same size, with no icon: "Sunset 16:38" over "Sunrise 07:05".
            child.setViewVisibility(R.id.chip_icon, View.GONE)
            child.setViewLayoutMargin(R.id.chip_text, RemoteViews.MARGIN_START, 0f, dip)
            child.setTextViewTextSize(R.id.chip_text, sp, SUN_SP)
            child.setTextViewText(R.id.chip_text, value.wide?.substringBefore(" · ") ?: lines.first())
            shrink(context, child, R.id.chip_text, value.wide?.substringBefore(" · ") ?: lines.first(), SUN_SP, inner, 8f)
            child.setInt(R.id.chip_text, "setMaxWidth", dp(context, inner.toInt()))
            lines.getOrNull(1)?.let { line(context, child, R.id.chip_detail, it, SUN_SP, secondary, inner) }
        } else {
            child.setTextViewText(R.id.chip_text, lines.first())
            shrink(context, child, R.id.chip_text, lines.first(), STACK_TEXT_SP, inner - iconDp - 4, 9f)
            child.setInt(R.id.chip_text, "setMaxWidth", dp(context, (inner - iconDp - 4).toInt()))
            lines.getOrNull(1)?.let { line(context, child, R.id.chip_detail, it, STACK_LINE_SP, secondary, inner) }
            lines.getOrNull(2)?.let {
                // eToro's third line is the portfolio's value, larger and white; others match line 2.
                if (spec.kind == Complication.ETORO) line(context, child, R.id.chip_detail2, it, 12f, primary, inner)
                else line(context, child, R.id.chip_detail2, it, STACK_LINE_SP, secondary, inner)
            }
        }
        child.setContentDescription(R.id.chip_zone, "${spec.kind.label}: " + lines.joinToString(", "))
        return child
    }

    /** Shows one of a stacked chip's lines. */
    private fun line(context: Context, chip: RemoteViews, id: Int, text: String, sp: Float, colour: Int, roomDp: Float) {
        chip.setViewVisibility(id, View.VISIBLE)
        chip.setInt(id, "setMaxWidth", dp(context, roomDp.toInt()))
        chip.setTextViewText(id, text)
        chip.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        chip.setTextColor(id, colour)
        shrink(context, chip, id, text, sp, roomDp, 8f)
    }

    /**
     * The chip's background, icon and colours (chips spec 2.2 and 2.3): its tonal palette, as a
     * pill or, [stacked], a rounded card; on the always-on display, an outline in grey.
     */
    private fun look(child: RemoteViews, spec: ChipSpec, value: ChipValues.Value, aod: Boolean, stacked: Boolean = false) {
        val icon = value.parts.singleOrNull()?.let { if (aod) it.aodIcon else it.icon }
        val tone = ChipTones.of(spec, value)
        if (aod) {
            child.setInt(R.id.chip_pill, "setBackgroundResource", R.drawable.chip_bg_aod)
            child.setImageViewResource(R.id.chip_icon, icon ?: spec.kind.aodIcon)
            child.setTextColor(R.id.chip_text, Style.AOD_TEXT)
        } else {
            child.setInt(R.id.chip_pill, "setBackgroundResource", if (stacked) tone.stack else tone.pill)
            child.setImageViewResource(R.id.chip_icon, icon ?: spec.kind.icon)
            child.setInt(R.id.chip_icon, "setColorFilter", tone.icon)
            child.setTextColor(R.id.chip_text, tone.text)
        }
        // The app's own status-bar icon, in the chip's icon colour.
        if (value.appIcon != null) {
            child.setImageViewIcon(R.id.chip_icon, value.appIcon)
            child.setInt(R.id.chip_icon, "setColorFilter", if (aod) Style.AOD_TEXT else tone.icon)
        }
    }

    /**
     * A row of the extra chips card: the chips share the row's whole width, and each shows the
     * most it can in its share: its name and the whole story ("Screen time · 1h 20m · YouTube
     * 35m"), else the whole story, else the value. Bluetooth devices sit side by side.
     */
    fun addOverflowRow(context: Context, views: RemoteViews, placed: List<Placed>, rowWidthDp: Float, aod: Boolean, container: Int) {
        views.setViewVisibility(container, if (placed.isEmpty()) View.GONE else View.VISIBLE)
        if (placed.isEmpty()) return
        val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
        val px = android.util.TypedValue.COMPLEX_UNIT_PX
        val style = DEFAULT_STRIP
        val gap = style.gapDp
        // The chips share the row by weight (include_chip_big_fill), so they always fit it; the
        // width here only decides how much text to try, with a little to spare in case the card
        // is narrower than estimated.
        val width = (rowWidthDp - gap * (placed.size - 1)) / placed.size - TEXT_SPARE_DP
        val inner = width - 2 * style.padDp
        placed.forEachIndexed { i, (spec, value, big) ->
            val child = RemoteViews(context.packageName, R.layout.include_chip_big_fill)
            child.setViewLayoutHeight(R.id.chip_zone, style.zoneHDp.toFloat(), dip)
            if (i < placed.lastIndex) child.setViewLayoutMargin(R.id.chip_zone, RemoteViews.MARGIN_END, gap.toFloat(), dip)
            child.setViewLayoutWidth(R.id.chip_pill, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), px)
            child.setViewLayoutHeight(R.id.chip_pill, style.pillHDp.toFloat(), dip)
            child.setViewPadding(R.id.chip_pill, dp(context, style.padDp), 0, dp(context, style.padDp), 0)
            val tone = ChipTones.of(spec, value)
            child.setInt(R.id.chip_pill, "setBackgroundResource", if (aod) R.drawable.chip_bg_aod else tone.pill)
            child.setViewLayoutWidth(R.id.chip_icon, style.iconDp.toFloat(), dip)
            child.setViewLayoutHeight(R.id.chip_icon, style.iconDp.toFloat(), dip)
            child.setTextViewTextSize(R.id.chip_text, android.util.TypedValue.COMPLEX_UNIT_SP, style.textSp)
            if (value.parts.size > 1) {
                child.setViewVisibility(R.id.chip_icon, View.GONE)
                child.setViewVisibility(R.id.chip_text, View.GONE)
                var used = 0f
                value.parts.forEachIndexed { k, part ->
                    val w = 16 + 4 + textWidthDp(context, part.named, 12.5f) + if (k > 0) PART_GAP_DP else 0
                    if (k > 0 && used + w > inner) return@forEachIndexed
                    used += w
                    child.addView(R.id.chip_row, partView(context, part, aod, gap = k > 0, named = true, tone = tone))
                }
            } else {
                val name = label(context, spec)
                val story = if (big) value.wide ?: value.text else value.text
                val room = inner - style.iconDp - 4
                // The sun chip's story names itself ("Sunset 16:38 · Sunrise 07:05").
                val named = if (spec.kind == Complication.SUNSET) null else "$name · $story"
                val text = listOfNotNull(named, story, value.text).firstOrNull { textWidthDp(context, it, style.textSp) <= room } ?: value.text
                child.setTextViewText(R.id.chip_text, text)
                look(child, spec, value, aod)
                shrink(context, child, R.id.chip_text, text, style.textSp, room, 9f)
            }
            child.setContentDescription(R.id.chip_zone, "${label(context, spec)}: ${value.wide ?: value.text}")
            child.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
            views.addView(container, child)
        }
    }

    /**
     * The extra chips card's only chip: one pill across the full width and the height of both
     * rows. Line 1 is its name and value, larger; line 2 the whole story (or the second line it
     * would show when stacked). Bluetooth devices fill the two lines side by side.
     */
    fun addOverflowSingle(context: Context, views: RemoteViews, placed: Placed, widthDp: Float, heightDp: Float, aod: Boolean, container: Int) {
        val (spec, value, _) = placed
        val dip = android.util.TypedValue.COMPLEX_UNIT_DIP
        val px = android.util.TypedValue.COMPLEX_UNIT_PX
        val tone = ChipTones.of(spec, value)
        views.setViewVisibility(container, View.VISIBLE)
        val child = RemoteViews(context.packageName, R.layout.include_chip_big_fill)
        child.setViewLayoutHeight(R.id.chip_zone, heightDp, dip)
        child.setViewLayoutWidth(R.id.chip_pill, android.view.ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), px)
        child.setViewLayoutHeight(R.id.chip_pill, heightDp - 8, dip)
        child.setViewPadding(R.id.chip_pill, dp(context, 16), 0, dp(context, 16), 0)
        child.setInt(R.id.chip_pill, "setBackgroundResource", if (aod) R.drawable.chip_bg_aod else tone.pill)
        child.setViewLayoutWidth(R.id.chip_icon, 20f, dip)
        child.setViewLayoutHeight(R.id.chip_icon, 20f, dip)
        val inner = widthDp - 32 - TEXT_SPARE_DP
        if (value.parts.size > 1) {
            child.setViewVisibility(R.id.chip_icon, View.GONE)
            child.setViewVisibility(R.id.chip_text, View.GONE)
            val rows = List(2) { mutableListOf<ChipValues.Part>() }
            var row = 0
            var used = 0f
            for (part in value.parts) {
                val w = 16 + 4 + textWidthDp(context, part.named, 12.5f) + if (rows[row].isEmpty()) 0 else PART_GAP_DP
                if (rows[row].isNotEmpty() && used + w > inner) {
                    if (row == 1) break
                    row = 1
                    used = 0f
                }
                used += if (rows[row].isEmpty()) 16 + 4 + textWidthDp(context, part.named, 12.5f) else w
                rows[row] += part
            }
            rows[0].forEachIndexed { k, part -> child.addView(R.id.chip_row, partView(context, part, aod, gap = k > 0, named = true, tone = tone)) }
            if (rows[1].isNotEmpty()) {
                child.setViewVisibility(R.id.chip_row2, View.VISIBLE)
                rows[1].forEachIndexed { k, part -> child.addView(R.id.chip_row2, partView(context, part, aod, gap = k > 0, named = true, tone = tone)) }
            }
        } else {
            val name = label(context, spec)
            val headline = if (spec.kind == Complication.SUNSET) value.text else "$name · ${value.text}"
            child.setTextViewText(R.id.chip_text, headline)
            child.setTextViewTextSize(R.id.chip_text, android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            look(child, spec, value, aod)
            shrink(context, child, R.id.chip_text, headline, 16f, inner - 24, 11f)
            // The second line: the whole story when it says more than the value, else the stacked second line.
            val more = value.wide?.takeIf { it != value.text } ?: value.detail
            if (more != null) {
                child.setViewVisibility(R.id.chip_detail, View.VISIBLE)
                child.setTextViewText(R.id.chip_detail, more)
                child.setTextViewTextSize(R.id.chip_detail, android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                child.setTextColor(R.id.chip_detail, if (aod) Style.AOD_TEXT else tone.secondary)
                shrink(context, child, R.id.chip_detail, more, 12.5f, inner, 9f)
            }
        }
        child.setContentDescription(R.id.chip_zone, "${label(context, spec)}: ${value.wide ?: value.text}")
        child.setOnClickPendingIntent(R.id.chip_zone, spec.tapIntent(context))
        views.addView(container, child)
    }

    /** A chip's name as a reader would say it: "Screen time", "Heart rate", "Gmail", an app's name. */
    private fun label(context: Context, spec: ChipSpec): String = when (spec.kind) {
        Complication.HEALTH -> com.ataglanceplus.complications.HealthMetric.fromId(spec.arg)?.label ?: spec.kind.label
        Complication.GMAIL -> "Gmail"
        Complication.APP_NOTIFICATIONS -> spec.arg?.let { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrNull()
        } ?: spec.kind.label
        else -> spec.kind.label
    }

    /** One Bluetooth device: its icon and level, and its name where there is room ([named]). */
    private fun partView(context: Context, part: ChipValues.Part, aod: Boolean, gap: Boolean = false, named: Boolean = false, tone: ChipTone? = null, roomDp: Float? = null): RemoteViews {
        val row = RemoteViews(context.packageName, if (named) R.layout.include_chip_part_named else R.layout.include_chip_part)
        row.setImageViewResource(R.id.chip_icon, if (aod) part.aodIcon else part.icon)
        row.setTextViewText(R.id.chip_text, if (named) part.named else part.text)
        if (aod) row.setTextColor(R.id.chip_text, Style.AOD_TEXT)
        else if (tone != null) {
            row.setInt(R.id.chip_icon, "setColorFilter", tone.icon)
            row.setTextColor(R.id.chip_text, tone.text)
        }
        // Where room is short (a stacked chip's device name), shrink, then end in "…".
        roomDp?.let {
            shrink(context, row, R.id.chip_text, if (named) part.named else part.text, 12.5f, it, 10f)
            row.setInt(R.id.chip_text, "setMaxWidth", dp(context, it.toInt()))
        }
        if (gap) row.setViewLayoutMargin(R.id.chip_part, RemoteViews.MARGIN_START, PART_GAP_DP.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
        return row
    }

    /** Shrinks [text] in [id] from [sp] until it fits [roomDp] (not below [minSp]). */
    private fun shrink(context: Context, chip: RemoteViews, id: Int, text: String, sp: Float, roomDp: Float, minSp: Float) {
        val width = textWidthDp(context, text, sp)
        if (width > roomDp && roomDp > 0) {
            chip.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, maxOf(minSp, sp * roomDp / width))
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Makes [given] fit the pill (see [addPlaced]). */
    private fun fit(context: Context, chip: RemoteViews, style: ChipStyle, given: String, maxPillDp: Float? = null) {
        val room = (maxPillDp ?: style.maxPillDp?.toFloat())?.let { it - 2 * style.padDp } ?: Float.MAX_VALUE
        val withIcon = minOf(room - style.iconDp - 4, style.maxTextDp.toFloat())
        // "5h 13m" first loses its spaces ("5h13m"), so the icon can stay.
        var text = given
        if (textWidthDp(context, text, style.textSp) > withIcon && ' ' in text) {
            text = text.replace(" ", "")
            chip.setTextViewText(R.id.chip_text, text)
        }
        val width = textWidthDp(context, text, style.textSp)
        if (width <= withIcon) return
        val scale = withIcon / width
        if (scale >= 0.85f) {
            chip.setTextViewTextSize(R.id.chip_text, android.util.TypedValue.COMPLEX_UNIT_SP, style.textSp * scale)
            return
        }
        chip.setViewVisibility(R.id.chip_icon, View.GONE)
        val alone = minOf(room, style.maxTextDp.toFloat())
        if (width > alone) {
            chip.setTextViewTextSize(R.id.chip_text, android.util.TypedValue.COMPLEX_UNIT_SP, maxOf(8f, style.textSp * alone / width))
        }
    }

    /** The chips that currently have something to show, with their values. */
    fun withValues(specs: List<ChipSpec>): List<Pair<ChipSpec, ChipValues.Value>> {
        val values = ChipValues.values.value
        return specs.mapNotNull { spec ->
            val value = if (spec.kind == Complication.RAIL_TICKET) ChipValues.Value(spec.kind.sample) else values[spec.key]
            value?.let { spec to it }
        }
    }

    // Chips spec 2.1: two slots across and stacked.
    private const val ACROSS_PAD_DP = 10
    private const val ACROSS_GAP_DP = 6
    private const val PROGRESS_EXTRA_DP = 2
    private const val STACK_PAD_DP = 8
    private const val STACK_PAD_V_DP = 6
    private const val STACK_ICON_DP = 14
    private const val STACK_TEXT_SP = 13f
    private const val STACK_LINE_SP = 10f
    private const val SUN_SP = 11f
}
