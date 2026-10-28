package com.ataglanceplus.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One live session between a client (launcher, SystemUI) and our service. */
data class SessionInfo(
    val id: String,
    val surface: String,
    val clientPackage: String,
    val targetCount: Int,
    val lastPushAt: Long
)

/**
 * In-process view of what the At a Glance service is doing, for the status screen.
 * The service and the activity run in the same process, so a plain StateFlow is enough.
 */
object ServiceState {

    private val _sessions = MutableStateFlow<Map<String, SessionInfo>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionInfo>> = _sessions.asStateFlow()

    private val _lastEvent = MutableStateFlow("")
    val lastEvent: StateFlow<String> = _lastEvent.asStateFlow()

    fun sessionCreated(info: SessionInfo) = _sessions.update { it + (info.id to info) }

    fun sessionPushed(id: String, at: Long) = _sessions.update { map ->
        map[id]?.let { map + (id to it.copy(lastPushAt = at)) } ?: map
    }

    fun sessionDestroyed(id: String) = _sessions.update { it - id }

    fun event(description: String) {
        _lastEvent.value = description
    }
}
