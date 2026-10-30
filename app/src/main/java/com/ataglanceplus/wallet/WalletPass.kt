package com.ataglanceplus.wallet

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * One Google Wallet pass, parsed from its ValuableWrapper (see [WalletSource]). Only what the card,
 * the code pop-up and the rules need is kept. Field numbers are the plugin's (see NOTICE).
 *
 * A pass has no location of its own; where it appears is the user's rules. Flights, events and
 * transit also carry their own [start]/[end]/[showFrom] times, so they can appear automatically.
 */
data class WalletPass(
    val id: String,
    val kind: Kind,
    val active: Boolean,
    val title: String,
    val subtitle: String?,
    val issuer: String?,
    /** ARGB brand colour for the card. */
    val colour: Int,
    val logoUrl: String?,
    val barcode: Barcode?,
    /** Type's own time (flight departure, event start, transit departure), epoch millis, or null. */
    val start: Long?,
    val end: Long?,
    /** When Google itself would start surfacing the pass (notification time), epoch millis, or null. */
    val showFrom: Long?,
    /** A few label/value lines for the preview and pop-up. */
    val details: List<Pair<String, String>>,
) {
    enum class Kind { LOYALTY, GIFT, OFFER, EVENT, FLIGHT, TRANSIT, HEALTH, GENERIC }

    data class Barcode(val format: Format, val value: String, val displayText: String?)

    /** ZXing-renderable code formats, plus a text-only fallback that renders as plain text. */
    enum class Format { AZTEC, CODE_39, CODE_128, CODABAR, DATA_MATRIX, EAN_8, EAN_13, ITF_14, PDF_417, QR, UPC_A, UPC_E, TEXT_ONLY, UNKNOWN }

    /** True for a pass whose time has clearly passed (yesterday or earlier). */
    fun expired(now: Long): Boolean {
        val when_ = end ?: start ?: return false
        return when_ < now - 24L * 60 * 60 * 1000
    }

    companion object {

        private const val DEFAULT_COLOUR = 0xFF3A305D.toInt()

        /** The oneof field number in ValuableWrapper for each pass type. */
        private val TYPES = listOf(
            1 to Kind.LOYALTY, 2 to Kind.GIFT, 3 to Kind.OFFER, 4 to Kind.EVENT,
            5 to Kind.FLIGHT, 6 to Kind.TRANSIT, 7 to Kind.HEALTH,
            8 to Kind.GENERIC, 9 to Kind.GENERIC, // 9 = sensitive generic, shown as generic
        )

        fun parse(id: String, wrapper: Proto): WalletPass {
            val (typeField, kind) = TYPES.firstOrNull { wrapper.has(it.first) } ?: (8 to Kind.GENERIC)
            val body = wrapper.msg(typeField) ?: Proto(ByteArray(0))

            // Field layout per type: metadata, issuer, redemption. Loyalty/gift/offer start at 3;
            // everything else at 2.
            val base = if (kind == Kind.LOYALTY || kind == Kind.GIFT || kind == Kind.OFFER) 3 else 2
            val metadata = body.msg(base)
            val issuer = body.msg(base + 1)
            val redemption = body.msg(base + 2)

            val active = metadata?.let { !it.has(5) || it.bool(5) } ?: true

            // IssuerInfo: title 2, issuer_name 3, logo 9 {url 1}, background_color 13.
            val title = issuer?.str(2) ?: id
            val issuerName = issuer?.str(3)
            val logoUrl = issuer?.msg(9)?.str(1)
            val colour = parseColour(issuer?.msg(13)) ?: DEFAULT_COLOUR

            val barcode = parseBarcode(redemption)

            val details = mutableListOf<Pair<String, String>>()
            var start: Long? = null
            var end: Long? = null
            var showFrom: Long? = null
            var subtitle: String? = issuerName

            when (kind) {
                Kind.EVENT -> {
                    // venue 6 {name 2, address 3}; date_time 7 {doors 2, start 3, end 4}; seat 8;
                    // notification 16 { date_time_to_show 7 }.
                    body.msg(6)?.let { venue ->
                        venue.str(2)?.let { subtitle = it; details += "Venue" to it }
                        venue.str(3)?.let { details += "Address" to it }
                    }
                    body.msg(7)?.let { dt ->
                        start = dateTime(dt.msg(3)); end = dateTime(dt.msg(4))
                    }
                    body.msg(8)?.let { seat ->
                        val line = listOfNotNull(
                            seat.str(3)?.let { "Sec $it" }, seat.str(2)?.let { "Row $it" },
                            seat.str(1)?.let { "Seat $it" }, seat.str(4)?.let { "Gate $it" },
                        ).joinToString(" · ")
                        if (line.isNotEmpty()) details += "Seat" to line
                    }
                    showFrom = dateTime(body.msg(16)?.msg(7))
                }
                Kind.FLIGHT -> {
                    // carrier+number 5; origin 6 / destination 7 {city 1, iata 3, terminal 4, gate 5};
                    // google_status 20 { sched_dep 8, sched_arr 10, gate_closes 12 }; boarding 10
                    // { group 1, seat 2, class 3 }; reservation 12 { code 1 }; notification 16.
                    val origin = body.msg(6)
                    val dest = body.msg(7)
                    val from = origin?.str(3) ?: origin?.str(1)
                    val to = dest?.str(3) ?: dest?.str(1)
                    if (from != null && to != null) subtitle = "$from → $to"
                    body.str(5)?.let { details += "Flight" to it }
                    dest?.str(4)?.let { details += "Terminal" to it }
                    (dest?.str(5) ?: origin?.str(5))?.let { details += "Gate" to it }
                    body.msg(10)?.let { b ->
                        b.str(2)?.let { details += "Seat" to it }
                        b.str(3)?.let { details += "Class" to it }
                    }
                    body.msg(12)?.str(1)?.let { details += "Confirmation" to it }
                    body.msg(20)?.let { s ->
                        start = dateTime(s.msg(8)); end = dateTime(s.msg(10))
                    }
                    showFrom = dateTime(body.msg(16)?.msg(7))
                }
                Kind.TRANSIT -> {
                    // notification 7; departure 8; arrival 9 (each a DateTime).
                    start = dateTime(body.msg(8)); end = dateTime(body.msg(9))
                    showFrom = dateTime(body.msg(7)?.msg(7))
                }
                else -> Unit
            }

            // GroupingInfo (loyalty/gift/offer 17/18, else 5): title 3, subtitle 4.
            val groupingField = when (kind) {
                Kind.LOYALTY, Kind.GIFT -> 17
                Kind.OFFER -> 18
                else -> 5
            }
            body.msg(groupingField)?.str(4)?.let { if (subtitle == null) subtitle = it }

            return WalletPass(
                id = id, kind = kind, active = active, title = title, subtitle = subtitle,
                issuer = issuerName, colour = colour, logoUrl = logoUrl, barcode = barcode,
                start = start, end = end, showFrom = showFrom, details = details,
            )
        }

        private fun parseBarcode(redemption: Proto?): Barcode? {
            redemption ?: return null
            if (redemption.bool(5)) return null                    // hide_barcode
            val code = redemption.msg(2) ?: return null             // RedemptionInfo.barcode
            val value = code.str(2) ?: redemption.str(1) ?: return null
            return Barcode(format(code.int(1)), value, code.str(3))
        }

        private fun format(type: Int): Format = when (type) {
            2 -> Format.AZTEC; 3 -> Format.CODE_39; 5 -> Format.CODE_128; 6 -> Format.CODABAR
            7 -> Format.DATA_MATRIX; 8 -> Format.EAN_8; 9 -> Format.EAN_13; 10 -> Format.ITF_14
            11 -> Format.PDF_417; 14 -> Format.QR; 15 -> Format.UPC_A; 16 -> Format.UPC_E
            19 -> Format.TEXT_ONLY
            else -> Format.UNKNOWN
        }

        /** Color: red 1, green 2, blue 3 (fixed32 floats 0..1); alpha 4 { value 1 }. */
        private fun parseColour(colour: Proto?): Int? {
            colour ?: return null
            if (!colour.has(1) && !colour.has(2) && !colour.has(3)) return null
            val r = (colour.float(1).coerceIn(0f, 1f) * 255).toInt()
            val g = (colour.float(2).coerceIn(0f, 1f) * 255).toInt()
            val b = (colour.float(3).coerceIn(0f, 1f) * 255).toInt()
            val a = (colour.msg(4)?.float(1)?.coerceIn(0f, 1f) ?: 1f).let { (it * 255).toInt() }
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** A Wallet DateTime { local_date 1, local_time 2, offset_from_utc 3 } as epoch millis. */
        private fun dateTime(dt: Proto?): Long? {
            dt ?: return null
            val date = dt.msg(1) ?: return null
            val year = date.int(1); val month = date.int(2); val day = date.int(3)
            if (year == 0 || month == 0 || day == 0) return null
            val time = dt.msg(2)
            val local = LocalDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(time?.int(1) ?: 0, time?.int(2) ?: 0, time?.int(3) ?: 0),
            )
            val offsetSeconds = dt.msg(3)?.int(1) ?: 0
            return local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
        }
    }
}
