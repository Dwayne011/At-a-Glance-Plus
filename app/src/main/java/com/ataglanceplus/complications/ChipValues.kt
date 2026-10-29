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
    )

    data class Part(val icon: Int, val aodIcon: Int, val text: String)

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
