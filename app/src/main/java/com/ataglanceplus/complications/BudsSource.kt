package com.ataglanceplus.complications

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.ataglanceplus.R

/**
 * Battery levels of connected Bluetooth devices that report one (earbuds, headphones, watches,
 * keyboards, mice). The chip shows each in turn, with an icon for the kind of device.
 *
 * Experimental: Android only shares this through a broadcast and a hidden method
 * (BluetoothDevice.getBatteryLevel), so it may not work on every device or Android version.
 * Pixel Buds report one level here, not each bud and the case separately.
 */
class BudsSource(private val context: Context, private val onChange: () -> Unit) {

    /** Level and icon by device address, for devices that are connected. */
    private val levels = LinkedHashMap<String, ChipValues.Part>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    levels.remove(device.address)
                    if (level >= 0) levels[device.address] = part(device, level)
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

    /** Every connected device's level; the chip takes turns between them. */
    fun value(): ChipValues.Value? {
        val parts = levels.values.toList()
        if (parts.isEmpty()) return null
        // Extended, a lone device shows its name too.
        return ChipValues.Value(parts.first().text, parts = parts, wide = parts.joinToString(" · ") { it.named }, detail = parts.first().name)
    }

    /** Headphones, a watch, or anything else, from the device's Bluetooth class. */
    @SuppressLint("MissingPermission") // only called once the permission is held
    private fun part(device: BluetoothDevice, level: Int): ChipValues.Part {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        val (icon, aod) = when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_comp_buds to R.drawable.ic_comp_buds_aod
            BluetoothClass.Device.Major.WEARABLE -> R.drawable.ic_comp_watch to R.drawable.ic_comp_watch_aod
            else -> R.drawable.ic_comp_bluetooth to R.drawable.ic_comp_bluetooth_aod
        }
        // The name the user gave it, else its own; shortened so a chip can still hold it.
        val name = runCatching { device.alias ?: device.name }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }
        return ChipValues.Part(icon, aod, "$level%", name)
    }

    /** Levels for devices already connected when we start listening. */
    @SuppressLint("MissingPermission") // checked by hasPermission
    private fun readConnected() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val devices = runCatching { adapter.bondedDevices }.getOrNull() ?: return
        devices.forEach { device ->
            try {
                val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
                val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
                if (connected && level >= 0) levels[device.address] = part(device, level)
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
