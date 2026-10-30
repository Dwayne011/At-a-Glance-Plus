package com.ataglanceplus.complications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The latest value of every chip, by chip key ("battery", "gmail:me@example.com", …).
 * A chip with nothing to show has no entry, and is left off the card.
 */
object ChipValues {

    /**
     * A chip's value. [parts], when there is more than one, are shown in turn (one per connected
     * Bluetooth device), or side by side on a big chip; a single part's icon replaces the chip's
     * usual icon.
     */
    data class Value(
        val text: String,
        val attention: Boolean = false,
        val parts: List<Part> = emptyList(),
        /** The app's own status-bar icon, used in place of the chip's icon (app-notification chips). */
        val appIcon: android.graphics.drawable.Icon? = null,
        /** The whole story on one line, for a wide chip: "4d · Mon 2 Nov". Defaults to [text]. */
        val wide: String? = null,
        /** A second line under [text], for a tall chip: "Mon 2 Nov". */
        val detail: String? = null,
        /**
         * The lines of a chip stacked over two slots, when they differ from [text] and [detail]:
         * the first sits beside the icon ("4d"), the rest under it ("Holiday", "Mon 2 Nov").
         */
        val stacked: List<String>? = null,
        /** How far along, 0 to 1, drawn as a thin line on an extended chip (steps toward the goal). */
        val progress: Float? = null,
        /** A fall, for a chip that can go either way (eToro): drawn in its red tones. */
        val down: Boolean = false,
        /** Shorter [text] for a one-slot pill it does not fit ("+6.3%" for "+$412 · +6.3%"). */
        val compact: String? = null,
    ) {
        /** The stacked chip's lines: [stacked], else the value with its second line. */
        val lines: List<String> get() = stacked ?: listOfNotNull(text, detail)
    }

    data class Part(val icon: Int, val aodIcon: Int, val text: String, val name: String? = null) {
        /** With its name, where there is room: "Pixel Buds Pro 100%". */
        val named: String get() = name?.let { "$it $text" } ?: text
    }

    private val _values = MutableStateFlow<Map<String, Value>>(emptyMap())
    val values: StateFlow<Map<String, Value>> = _values.asStateFlow()

    fun set(key: String, value: Value?) {
        _values.update { if (value == null) it - key else it + (key to value) }
    }

    /** Drops values for chips that are no longer selected (for example a removed account). */
    fun retain(keys: Set<String>) {
        _values.update { current -> current.filterKeys { it in keys } }
    }
}
