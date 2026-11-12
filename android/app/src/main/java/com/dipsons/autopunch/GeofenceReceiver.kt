package com.dipsons.autopunch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Geofence transition -> hand the event to WorkManager, which uploads it (and retries when there is no network). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val event = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL, Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }
        val loc = ev.triggeringLocation
        val locId = ev.triggeringGeofences?.firstOrNull()?.requestId?.removePrefix("loc_")?.toIntOrNull() ?: 0
        val mock = isMock(loc)
        UploadWorker.enqueue(context, event, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, loc?.accuracy ?: -1f, mock, locId)
    }

    @Suppress("DEPRECATION")
    private fun isMock(l: android.location.Location?): Boolean {
        if (l == null) return false
        return if (android.os.Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
    }
}
