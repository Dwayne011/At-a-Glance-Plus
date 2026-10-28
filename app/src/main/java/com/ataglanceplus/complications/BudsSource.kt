package com.ataglanceplus.complications

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log

/**
 * Battery level of connected earbuds or headphones, as they report it over Bluetooth.
 *
 * Experimental: Android only shares this through a broadcast and a hidden method
 * (BluetoothDevice.getBatteryLevel), so it may not work on every device or Android version.
 * Pixel Buds report one level here, not each bud and the case separately.
 */
class BudsSource(private val context: Context, private val onChange: () -> Unit) {

    /** Level by device address, for devices that are connected. */
    private val levels = LinkedHashMap<String, Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    levels.remove(device.address)
                    if (level >= 0) levels[device.address] = level
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> levels.remove(device.address)
            }
            onChange()
        }
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) return
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)
        readConnected()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** The most recently reported level of a connected device. */
    fun value(): ChipValues.Value? = levels.values.lastOrNull()?.let { ChipValues.Value("$it%") }

    /** Levels for devices already connected when we start listening. */
    @SuppressLint("MissingPermission") // checked by hasPermission
    private fun readConnected() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val devices = runCatching { adapter.bondedDevices }.getOrNull() ?: return
        devices.forEach { device ->
            try {
                val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
                val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
                if (connected && level >= 0) levels[device.address] = level
            } catch (e: Exception) {
                Log.d(TAG, "battery level not readable: ${e.javaClass.simpleName}")
                return
            }
        }
    }

    companion object {
        private const val TAG = "BudsSource"
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
