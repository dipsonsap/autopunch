package com.dipsons.autopunch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray

/** Registers one geofence per company location (the server decides the final in / out, using ALL locations). */
object Geo {
    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, GeofenceReceiver::class.java)
        // geofencing needs a MUTABLE PendingIntent on Android 12+
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(c, 1001, i, flags)
    }

    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        val arr = try { JSONArray(Prefs.locations(c)) } catch (e: Exception) { JSONArray() }
        val client = LocationServices.getGeofencingClient(c)
        client.removeGeofences(pending(c))
        if (arr.length() == 0 || !Prefs.linked(c)) return
        val list = ArrayList<Geofence>()
        for (n in 0 until minOf(arr.length(), 90)) {
            val o = arr.getJSONObject(n)
            list.add(
                Geofence.Builder()
                    .setRequestId("loc_" + o.getInt("id"))
                    .setCircularRegion(o.getDouble("lat"), o.getDouble("lng"), maxOf(o.getDouble("radius").toFloat(), 100f))
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setLoiteringDelay(60000) // must stay inside for 1 minute before "arrived" counts (ignores drive-bys)
                    .build()
            )
        }
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL) // already inside when registered -> counts as arrived
            .addGeofences(list)
            .build()
        try { client.addGeofences(req, pending(c)) } catch (e: SecurityException) { /* location permission missing: the main screen asks for it */ }
    }
}
