package com.ataglanceplus.wallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent

/** A geofence enter/exit on a Wallet place: note the fix and let the service redraw the deck. */
class WalletGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WalletGeofences.ACTION_TRANSITION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        WalletGeofences.note(event.triggeringLocation)
    }
}
