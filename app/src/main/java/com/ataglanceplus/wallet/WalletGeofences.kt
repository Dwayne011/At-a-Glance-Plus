package com.ataglanceplus.wallet

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ataglanceplus.DebugLog
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Watches the places in the user's Wallet rules with Play services geofences, so a pass appears as
 * the phone arrives and goes when it leaves, even with the app in the background. Play services
 * does the watching (it, not this app, is what uses the location), and tells the app on arrival
 * and departure; the app keeps which passes' places the phone is inside. Also keeps a recent
 * location, so the deck can decide what to show the moment it is drawn.
 *
 * Geofences need precise location, location all the time, location switched on and Google
 * Location Accuracy; what is missing, and Play services' answer to each registration, is kept in
 * [status] and the debug history, so a place that never triggers can be explained.
 */
object WalletGeofences {

    private const val TAG = "WalletGeofences"
    const val ACTION_TRANSITION = "com.ataglanceplus.wallet.GEOFENCE"
    private const val MAX_GEOFENCES = 95
    /** Play services is unreliable below about 100 m, so no geofence is smaller (the rule's own radius still counts for a fix). */
    const val MIN_GEOFENCE_M = 100f

    /** Bumped whenever a geofence fires or a fresh location arrives, so the service redraws. */
    private val _changed = MutableStateFlow(0L)
    val changed: StateFlow<Long> = _changed.asStateFlow()

    @Volatile var cachedLocation: Location? = null
        private set

    /** Where the geofences stand: registered (how many), or why not. */
    data class Status(val at: Long, val ok: Boolean, val message: String, val count: Int = 0)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** Geofence ids the phone is inside, from Play services' arrival and departure events. */
    private val _inside = MutableStateFlow<Set<String>>(emptySet())
    val inside: StateFlow<Set<String>> = _inside.asStateFlow()

    /** The pass a geofence id belongs to ("passId#index"). */
    fun passOf(requestId: String): String = requestId.substringBeforeLast('#')

    /** Passes with a place the phone is inside. */
    fun insidePasses(): Set<String> = _inside.value.map(::passOf).toSet()

    /** Each registered geofence id's place label, for the history. */
    @Volatile private var labels: Map<String, String> = emptyMap()

    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context): Boolean = hasPrecise(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean =
        runCatching { context.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WalletGeofenceReceiver::class.java).setAction(ACTION_TRANSITION)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun report(context: Context, ok: Boolean, message: String, count: Int = 0) {
        val previous = _status.value
        _status.value = Status(System.currentTimeMillis(), ok, message, count)
        // The history gets each change, not each identical re-registration.
        if (previous?.message != message) DebugLog.add(context, DebugLog.Kind.LOCATION, message)
    }

    /** What stops geofences working now, or null if nothing does. */
    fun blocker(context: Context): String? = when {
        !hasLocation(context) -> "Location permission is off"
        !hasPrecise(context) -> "Location is set to approximate; places need precise location"
        !hasBackground(context) -> "Location is allowed only while the app is open; places need \"Allow all the time\""
        !locationOn(context) -> "Location is switched off on the phone"
        else -> null
    }

    /** Re-registers geofences for every place in the rules. Call after the rules or passes change. */
    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        val app = context.applicationContext
        val places0 = WalletRules.allPlaces(app)
        if (places0.isEmpty()) {
            runCatching { LocationServices.getGeofencingClient(app).removeGeofences(pendingIntent(app)) }
            _inside.value = emptySet()
            report(app, true, "No places in the Wallet rules; nothing to watch")
            return
        }
        blocker(app)?.let {
            report(app, false, "Places not watched: $it")
            return
        }
        val client = LocationServices.getGeofencingClient(app)
        client.removeGeofences(pendingIntent(app))
        // Registering again re-sends "arrived" for places the phone is in (initial trigger).
        _inside.value = emptySet()

        // Play services allows 100 geofences an app: the nearest, when chains bring many branches.
        val here = cachedLocation
        val places = if (here == null || places0.size <= MAX_GEOFENCES) places0.take(MAX_GEOFENCES)
            else places0.sortedBy { (_, p) -> FloatArray(1).also { Location.distanceBetween(here.latitude, here.longitude, p.lat, p.lng, it) }[0] }.take(MAX_GEOFENCES)

        // One geofence per place; its id carries the pass id so the receiver knows the pass.
        val ids = mutableMapOf<String, String>()
        val geofences = places.mapIndexed { index, (passId, place) ->
            val id = "$passId#$index"
            ids[id] = place.label
            Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(place.lat, place.lng, maxOf(place.radiusM.toFloat(), MIN_GEOFENCE_M))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                // Arrivals within about a minute rather than whenever Play services next looks.
                .setNotificationResponsiveness(60_000)
                .build()
        }
        labels = ids
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        runCatching {
            client.addGeofences(request, pendingIntent(app))
                .addOnSuccessListener { report(app, true, "Watching ${geofences.size} place${if (geofences.size == 1) "" else "s"}", geofences.size) }
                .addOnFailureListener { e -> report(app, false, "Play services refused the places: ${explain(e)}") }
        }.onFailure { e ->
            Log.w(TAG, "addGeofences failed", e)
            report(app, false, "Places not watched: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Play services' refusal in words, with what to do about the usual one. */
    private fun explain(e: Exception): String {
        val code = (e as? ApiException)?.statusCode ?: return e.message ?: e.javaClass.simpleName
        return when (code) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                "geofencing is not available. Turn on Google Location Accuracy (Settings › Location › Location services › Google Location Accuracy) and location"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "too many places (Play services allows 100)"
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> "too many requests from this app"
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> "location permission is not enough (precise and all the time are needed)"
            GeofenceStatusCodes.GEOFENCE_REQUEST_TOO_FREQUENT -> "asked too often; it will try again later"
            else -> GeofenceStatusCodes.getStatusCodeString(code) + " ($code)"
        }
    }

    /** Arrived at or left places (from [WalletGeofenceReceiver]). */
    fun transition(context: Context, entered: Boolean, ids: List<String>, location: Location?) {
        _inside.update { if (entered) it + ids else it - ids.toSet() }
        WalletSource.ensureLoaded(context)
        val passes = WalletSource.passes.value.associateBy { it.id }
        ids.forEach { id ->
            val place = labels[id] ?: "a place"
            val pass = passes[passOf(id)]?.title ?: "a pass"
            DebugLog.add(
                context, DebugLog.Kind.LOCATION,
                (if (entered) "Arrived at $place ($pass)" else "Left $place ($pass)") +
                    (location?.let { " · fix ±${it.accuracy.toInt()} m" } ?: ""),
            )
        }
        note(location)
    }

    /** Fetches a current location into [cachedLocation] and bumps [changed]. */
    @SuppressLint("MissingPermission")
    fun refreshLocation(context: Context) {
        if (!hasLocation(context)) return
        val app = context.applicationContext
        val client = LocationServices.getFusedLocationProviderClient(app)
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        cachedLocation = location
                        _changed.value = System.currentTimeMillis()
                        refreshChains(app, location)
                    } else {
                        DebugLog.add(app, DebugLog.Kind.LOCATION, "No location fix (Android gave none; ${blocker(app) ?: "location may be busy or unavailable"})")
                    }
                }
                .addOnFailureListener { e -> DebugLog.add(app, DebugLog.Kind.LOCATION, "Location fix failed: ${e.message ?: e.javaClass.simpleName}") }
        }.onFailure { Log.w(TAG, "location failed", it) }
    }

    /** Looks up the user's chains' branches near [location] (off the main thread) and watches them. */
    fun refreshChains(context: Context, location: Location) {
        val chains = WalletRules.chains(context)
        if (chains.isEmpty()) return
        Thread {
            if (PlaceSearch.refreshBranches(context, chains, location)) {
                refresh(context)
                _changed.value = System.currentTimeMillis()
            }
        }.start()
    }

    fun note(location: Location?) {
        if (location != null) cachedLocation = location
        _changed.value = System.currentTimeMillis()
    }
}
