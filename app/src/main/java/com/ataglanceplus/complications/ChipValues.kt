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

    data class Value(val text: String, val attention: Boolean = false)

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
