package com.dipsons.autopunch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Geofences are cleared by a reboot or an app update: register them again from the saved locations, then refresh from the server. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.linked(context)) return
        Geo.register(context)
        ConfigWorker.schedule(context)
    }
}
