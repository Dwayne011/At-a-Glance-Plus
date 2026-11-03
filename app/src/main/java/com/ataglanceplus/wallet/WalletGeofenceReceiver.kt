package com.ataglanceplus.wallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ataglanceplus.DebugLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/** Arrived at or left a Wallet place: keep which places the phone is in, and let the service redraw the deck. */
class WalletGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WalletGeofences.ACTION_TRANSITION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            DebugLog.add(context, DebugLog.Kind.LOCATION, "Place event error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)} (${event.errorCode})")
            return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> WalletGeofences.transition(context, entered = true, ids = ids, location = event.triggeringLocation)
            Geofence.GEOFENCE_TRANSITION_EXIT -> WalletGeofences.transition(context, entered = false, ids = ids, location = event.triggeringLocation)
            else -> WalletGeofences.note(event.triggeringLocation)
        }
    }
}
