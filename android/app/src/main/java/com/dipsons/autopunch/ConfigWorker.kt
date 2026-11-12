package com.dipsons.autopunch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Downloads the company locations and re-registers the geofences (every 6 hours, after boot, and when the app is opened). */
class ConfigWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!Prefs.linked(ctx)) return Result.success()
        return if (refresh(ctx)) Result.success() else Result.retry()
    }

    companion object {
        /** returns true when the server answered (even if it said "unlinked") */
        fun refresh(c: Context): Boolean {
            val r = try { Api.post("autopunch_config.php", JSONObject().put("token", Prefs.token(c))) } catch (e: Exception) { return false }
            if (r.code == 401) { Prefs.clear(c); Geo.register(c); return true }
            val j = r.json ?: return false
            if (!j.optBoolean("ok")) return false
            Prefs.saveLocations(c, j.optJSONArray("locations")?.toString() ?: "[]")
            Geo.register(c)
            return true
        }

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<ConfigWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("config", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
