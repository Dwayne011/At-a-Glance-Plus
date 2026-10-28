package com.ataglanceplus.live

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import com.ataglanceplus.complications.NotificationCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is playing now, from any app's media session. */
data class MediaNow(
    val pkg: String,
    val app: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val durationMs: Long,
    /** Position at [positionAt] (SystemClock.elapsedRealtime), for working out the position later. */
    val positionMs: Long,
    val positionAt: Long,
    val playing: Boolean,
    val open: PendingIntent?,
) {
    fun positionNow(): Long =
        if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs
}

/**
 * Follows the phone's media sessions (the same ones the media controls in the notification shade
 * use). The card shows the session that is playing, or one paused in the last 5 minutes so it
 * can be resumed; after that it goes away.
 *
 * Needs notification access: Android only lets a notification listener see other apps' sessions.
 */
class MediaSource(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val controllers = mutableListOf<MediaController>()
    private val pausedAt = HashMap<String, Long>()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
        override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
        override fun onSessionDestroyed() = recompute()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }
    private val expire = Runnable { recompute() }

    fun start() {
        if (started) return
        try {
            val component = NotificationCounter.component(context)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            track(manager.getActiveSessions(component))
            started = true
        } catch (e: SecurityException) {
            Log.i(TAG, "no notification access yet; media card waits for it")
        }
    }

    fun stop() {
        if (!started) return
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        handler.removeCallbacks(expire)
        started = false
        _now.value = null
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        controllers += list
        controllers.forEach { it.registerCallback(callback, handler) }
        recompute()
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        controllers.forEach { c ->
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) pausedAt.remove(c.packageName)
            else pausedAt.putIfAbsent(c.packageName, now)
        }
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
        val chosen = playing ?: controllers
            .filter { it.metadata != null && (now - (pausedAt[it.packageName] ?: 0L)) < LINGER_MS }
            .maxByOrNull { pausedAt[it.packageName] ?: 0L }
        current = chosen
        _now.value = chosen?.let { describe(it) }
        handler.removeCallbacks(expire)
        if (chosen != null && chosen !== playing) handler.postDelayed(expire, LINGER_MS)
    }

    private fun describe(c: MediaController): MediaNow {
        val meta = c.metadata
        val state = c.playbackState
        val art = (meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.let { shrink(it) }
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationInfo(c.packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString() }
            .getOrDefault(c.packageName)
        return MediaNow(
            pkg = c.packageName,
            app = app,
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: app,
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = art,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = state?.position ?: 0L,
            positionAt = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            open = c.sessionActivity,
        )
    }

    /** Album art goes to the launcher in every update, so keep it small (48dp at up to 3x). */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val max = 144
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
        return bitmap.scale((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "MediaSource"
        private const val LINGER_MS = 5 * 60 * 1000L

        private val _now = MutableStateFlow<MediaNow?>(null)
        val now: StateFlow<MediaNow?> = _now.asStateFlow()

        /** The session on the card, for the play/pause and skip buttons. */
        @Volatile
        private var current: MediaController? = null

        fun togglePlay() {
            val c = current ?: return
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }

        fun previous() = current?.transportControls?.skipToPrevious()
        fun next() = current?.transportControls?.skipToNext()
    }
}
