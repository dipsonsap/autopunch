package com.dipsons.autopunch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Sends one enter / exit event. Survives reboots and no-network: WorkManager keeps it and retries with back-off; the server de-duplicates by uid. */
class UploadWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        if (!Prefs.linked(ctx)) return Result.failure()
        val d = inputData
        val body = JSONObject()
            .put("token", Prefs.token(ctx))
            .put("uid", d.getString("uid"))
            .put("event", d.getString("event"))
            .put("lat", d.getDouble("lat", 0.0))
            .put("lng", d.getDouble("lng", 0.0))
            .put("accuracy", d.getFloat("acc", -1f).toDouble())
            .put("mock", d.getBoolean("mock", false))
            .put("loc_id", d.getInt("loc", 0))
            .put("client_time", d.getLong("time", System.currentTimeMillis()))
        val r = try { Api.post("autopunch_event.php", body) } catch (e: Exception) { return Result.retry() }
        if (r.code == 401) { Prefs.clear(ctx); Geo.register(ctx); Prefs.saveLast(ctx, "This phone was unlinked. Open the app and link it again."); return Result.failure() }
        if (r.code >= 500 || r.json == null) return Result.retry()
        val msg = r.json.optString("message", "")
        val result = r.json.optString("result", "")
        val stamp = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date())
        Prefs.saveLast(ctx, "$stamp\n$msg")
        if (result == "punched_in" || result == "punched_out") notify(msg)
        return Result.success()
    }

    private fun notify(text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("punch", "Attendance", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(ctx, "punch").setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("Attendance").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    companion object {
        fun enqueue(c: Context, event: String, lat: Double, lng: Double, acc: Float, mock: Boolean, locId: Int) {
            val data = Data.Builder().putString("uid", UUID.randomUUID().toString().replace("-", "")).putString("event", event)
                .putDouble("lat", lat).putDouble("lng", lng).putFloat("acc", acc).putBoolean("mock", mock).putInt("loc", locId)
                .putLong("time", System.currentTimeMillis()).build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c).enqueue(req)
        }
    }
}
