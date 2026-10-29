package com.ataglanceplus.live

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The torch while it is on: its brightness [level] out of [max] (max 1 when it has no levels). */
data class TorchNow(val cameraId: String, val level: Int, val max: Int)

/**
 * Whether the torch is on, however it was turned on (quick settings, Google Assistant, another
 * app), and its brightness. Turning it off or changing the brightness needs no permission.
 */
object TorchSource {

    private const val TAG = "TorchSource"

    /** Steps on the card's bar; phones with fewer levels use fewer. */
    const val STEPS = 8

    private val _torch = MutableStateFlow<TorchNow?>(null)
    val torch: StateFlow<TorchNow?> = _torch.asStateFlow()

    private var callback: CameraManager.TorchCallback? = null

    fun start(context: Context) {
        if (callback != null) return
        val cameras = context.getSystemService(CameraManager::class.java)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (!hasFlash(cameras, cameraId)) return
                _torch.value = if (enabled) TorchNow(cameraId, currentLevel(cameras, cameraId), maxLevel(cameras, cameraId)) else null
            }

            override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                val now = _torch.value ?: return
                if (now.cameraId == cameraId) _torch.value = now.copy(level = newStrengthLevel)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                // The camera app has the flash; the torch is off meanwhile.
                if (_torch.value?.cameraId == cameraId) _torch.value = null
            }
        }
        cameras.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        callback = cb
    }

    fun stop(context: Context) {
        callback?.let { context.getSystemService(CameraManager::class.java).unregisterTorchCallback(it) }
        callback = null
        _torch.value = null
    }

    fun turnOff(context: Context) {
        val now = _torch.value ?: return
        runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(now.cameraId, false) }
            .onFailure { Log.w(TAG, "could not turn the torch off", it) }
    }

    /** Which of the bar's steps are lit, and how many steps there are. */
    fun steps(torch: TorchNow): Pair<Int, Int> {
        val count = minOf(STEPS, torch.max)
        if (count <= 1) return 1 to 1
        return ceilDiv(torch.level * count, torch.max).coerceIn(1, count) to count
    }

    /** Sets the brightness to [step] of the bar's steps (1 = dimmest). The torch stays on. */
    fun setStep(context: Context, step: Int) {
        val now = _torch.value ?: return
        val count = minOf(STEPS, now.max)
        if (count <= 1) return
        // The top of the step's range, so the card shows the same step back.
        val level = step.coerceIn(1, count) * now.max / count
        runCatching { context.getSystemService(CameraManager::class.java).turnOnTorchWithStrengthLevel(now.cameraId, level) }
            .onFailure { Log.w(TAG, "could not set the torch brightness", it) }
    }

    /** One step brighter (+1) or dimmer (−1); never dimmer than the lowest step. */
    fun nudge(context: Context, by: Int) {
        val now = _torch.value ?: return
        setStep(context, steps(now).first + by)
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun hasFlash(cameras: CameraManager, id: String): Boolean =
        runCatching { cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)

    private fun maxLevel(cameras: CameraManager, id: String): Int =
        runCatching { cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) }.getOrNull() ?: 1

    private fun currentLevel(cameras: CameraManager, id: String): Int =
        runCatching { cameras.getTorchStrengthLevel(id) }.getOrDefault(maxLevel(cameras, id))
}
