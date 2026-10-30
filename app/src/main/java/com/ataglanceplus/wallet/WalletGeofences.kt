package com.ataglanceplus.wallet

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the places in the user's Wallet rules with Play services geofences, so a pass appears as
 * the phone arrives and goes when it leaves — even with the app in the background. Also keeps a
 * recent location so the deck can decide what to show the moment it is drawn. Falls back to a plain
 * last-location check where background geofencing isn't permitted.
 */
object WalletGeofences {

    private const val TAG = "WalletGeofences"
    const val ACTION_TRANSITION = "com.ataglanceplus.wallet.GEOFENCE"

    /** Bumped whenever a geofence fires or a fresh location arrives, so the service redraws. */
    private val _changed = MutableStateFlow(0L)
    val changed: StateFlow<Long> = _changed.asStateFlow()

    @Volatile var cachedLocation: Location? = null
        private set

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackground(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WalletGeofenceReceiver::class.java).setAction(ACTION_TRANSITION)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Re-registers geofences for every place in the rules. Call after the rules or passes change. */
    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        if (!hasLocation(context) || !hasBackground(context)) return
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(pendingIntent(context))

        val places = WalletRules.allPlaces(context)
        if (places.isEmpty()) return

        // One geofence per place; its id carries the pass id so the receiver can refresh.
        val geofences = places.mapIndexed { index, (passId, place) ->
            Geofence.Builder()
                .setRequestId("$passId#$index")
                .setCircularRegion(place.lat, place.lng, place.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        runCatching { client.addGeofences(request, pendingIntent(context)) }
            .onFailure { Log.w(TAG, "addGeofences failed", it) }
    }

    /** Fetches a current location into [cachedLocation] and bumps [changed]. */
    @SuppressLint("MissingPermission")
    fun refreshLocation(context: Context) {
        if (!hasLocation(context)) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        cachedLocation = location
                        _changed.value = System.currentTimeMillis()
                    }
                }
        }.onFailure { Log.w(TAG, "location failed", it) }
    }

    fun note(location: Location?) {
        if (location != null) cachedLocation = location
        _changed.value = System.currentTimeMillis()
    }
}
