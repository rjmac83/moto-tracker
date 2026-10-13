package com.example.mototracker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where home is, and what the phone does about it (Jack, 2026-10-12).
 *
 * Two rules, and only two:
 *
 *  - **Arriving home ends the trip.** A trip forgotten at the front door kept the
 *    phone reporting a fix every 30 s all evening. Always on, whatever auto-resume
 *    says: it only ever closes a trip, never opens one.
 *  - **Leaving home opens a trip.** Off the bike, so crash detection waits; if the
 *    phone is at speed the existing pull-away rules turn it into a ride within
 *    seconds. This is auto-resume's job, so switching auto-resume off stops it.
 *
 * Leaving is noticed by a geofence, which the OS watches for us while the app is
 * asleep and the trip is closed. Arriving is noticed by the ride service's own
 * fixes, since a trip being open means they are already coming.
 */
object Home {

    /** Leaving this far from home opens a trip. */
    const val RADIUS_M = 150f

    /**
     * Arriving means this much closer than [RADIUS_M]. The gap is the point: GPS
     * wobble on the boundary must not be able to end a trip the geofence just opened.
     */
    const val ARRIVED_M = 100f

    /** A fix worse than this says nothing about whether the phone is home. */
    const val MAX_ACCURACY_M = 80f

    /** Two fixes in a row at home, so one stray fix cannot end a trip. */
    const val ARRIVED_FIXES = 2

    /** Fast fixes after the geofence opens a trip, so pulling away settles quickly. */
    const val BOOST_MS = 120_000L

    private const val PREFS = "home"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val REQUEST_ID = "home"

    fun isSet(ctx: Context) = prefs(ctx).contains(KEY_LAT)

    fun lat(ctx: Context): Double = Double.fromBits(prefs(ctx).getLong(KEY_LAT, 0L))
    fun lon(ctx: Context): Double = Double.fromBits(prefs(ctx).getLong(KEY_LON, 0L))

    fun set(ctx: Context, lat: Double, lon: Double) {
        prefs(ctx).edit()
            .putLong(KEY_LAT, lat.toRawBits()).putLong(KEY_LON, lon.toRawBits()).commit()
        watch.disarm()
        RideLog.write(ctx, "HOME", String.format(java.util.Locale.US, "home set to %.5f, %.5f", lat, lon))
        register(ctx)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        watch.disarm()
        RideLog.write(ctx, "HOME", "home cleared")
        runCatching { client(ctx).removeGeofences(pending(ctx)) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- arriving

    /** Remembers where this trip has been, so arriving can be told from never having left. */
    private val watch = Watch()

    /** A trip has just opened: it has to be seen away from home before home can end it. */
    fun tripOpened() = watch.disarm()

    /**
     * Called with every fix while a trip is open. True when the phone has arrived home
     * and the trip should end.
     */
    fun arrived(ctx: Context, lat: Double, lon: Double, accuracyM: Float, speedKmh: Double): Boolean {
        if (!isSet(ctx)) return false
        return watch.offer(distanceM(lat, lon, lat(ctx), lon(ctx)), accuracyM, speedKmh)
    }

    /**
     * The decision, with nothing Android in it so it can be tested on the desktop.
     *
     * Home only ends a trip that has been away from it first. Otherwise pressing
     * Start trip in the driveway, to load up, would be undone a minute later.
     */
    class Watch {
        private var away = false
        private var fixesAtHome = 0

        fun disarm() { away = false; fixesAtHome = 0 }

        fun offer(distanceM: Double, accuracyM: Float, speedKmh: Double): Boolean {
            if (accuracyM > MAX_ACCURACY_M) return false
            if (distanceM > RADIUS_M) { away = true; fixesAtHome = 0; return false }
            // Going past the house at speed is not arriving at it.
            if (!away || distanceM > ARRIVED_M || speedKmh >= Trip.RESUME_KMH) {
                fixesAtHome = 0
                return false
            }
            fixesAtHome++
            if (fixesAtHome < ARRIVED_FIXES) return false
            disarm()
            return true
        }
    }

    // ---------------------------------------------------------------- leaving

    private fun client(ctx: Context): GeofencingClient = LocationServices.getGeofencingClient(ctx)

    /** The OS wakes [HomeReceiver] with this when the phone leaves. Must be mutable. */
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, HomeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /**
     * Asks the OS to watch for leaving. Safe to call as often as is convenient: it
     * replaces itself. The OS forgets geofences at a reboot, when location is switched
     * off and on, and when Play services updates, so the app calls this on every start.
     * Without "allow all the time" for location it is refused, and that is logged.
     */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!isSet(ctx)) return
        val fence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(lat(ctx), lon(ctx), RADIUS_M)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        runCatching {
            client(ctx).addGeofences(request, pending(ctx))
                .addOnSuccessListener { RideLog.write(ctx, "HOME", "watching for leaving home") }
                .addOnFailureListener {
                    RideLog.write(ctx, "HOME", "could not watch home: ${it.message} — " +
                        "location needs \"Allow all the time\"")
                }
        }.onFailure { RideLog.write(ctx, "HOME", "could not watch home: ${it.message}") }
    }

    /** The phone has left home with no trip open: open one, as auto-resume would. */
    internal fun left(ctx: Context) {
        if (DeviceKey.current == null) return
        if (Trip.state(ctx) != Trip.State.IDLE) return
        if (!Trip.autoResume(ctx)) {
            RideLog.write(ctx, "HOME", "left home — auto-resume is off, no trip opened")
            return
        }
        RideLog.write(ctx, "HOME", "left home — opening a trip")
        Trip.boostCadence(BOOST_MS)
        Trip.startTrip(ctx)
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Woken by the OS when the phone leaves home, even with the app closed. */
class HomeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            RideLog.write(ctx, "HOME", "geofence error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        // Opening a trip tells the relay, which is a network call.
        val pending = goAsync()
        Thread {
            try { Home.left(ctx) } finally { pending.finish() }
        }.start()
    }
}
