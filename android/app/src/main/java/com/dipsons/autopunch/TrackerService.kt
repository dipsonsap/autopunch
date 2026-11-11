package com.dipsons.autopunch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone reporting to the dashboard: a foreground service (the notification you see) that sends a heartbeat - position, battery, network,
 * GPS, permission - to api/live_location.php. The server answers with how often to report (fast while somebody watches the live map) and whether
 * the dashboard asked for an immediate fix ("Locate now"). Heartbeats that cannot be sent (no internet) are queued and uploaded later in order.
 */
class TrackerService : Service() {
    private lateinit var exec: ScheduledExecutorService
    @Volatile private var lastLoc: Location? = null
    @Volatile private var running = false
    @Volatile private var intervalS = 60L
    private val queue = ArrayDeque<JSONObject>()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { lastLoc = it } }
    }
    private val statusRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SHUTDOWN) {
                // the phone is switching off: tell the server in the few seconds Android allows
                val t = Thread { try { val b = status(); b.put("event", "shutdown"); b.put("client_time", System.currentTimeMillis()); Api.post("live_location.php", b) } catch (e: Exception) { } }
                t.start(); try { t.join(3500) } catch (e: Exception) { }
            } else {
                runNow()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Prefs.linked(this)) { stopSelf(); return }
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { stopSelf(); return }
        alive = true; running = true
        exec = Executors.newSingleThreadScheduledExecutor()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN); addAction(LocationManager.PROVIDERS_CHANGED_ACTION); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusRx, f, Context.RECEIVER_EXPORTED) else registerReceiver(statusRx, f)
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val ncb = object : ConnectivityManager.NetworkCallback() { override fun onAvailable(network: Network) { runNow() } }
            cm.registerDefaultNetworkCallback(ncb); netCb = ncb
        } catch (e: Exception) { }
        requestUpdates()
        try { exec.schedule({ beat() }, 2, TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.linked(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; alive = false
        try { exec.shutdownNow() } catch (e: Exception) { }
        try { fused.removeLocationUpdates(cb) } catch (e: Exception) { }
        try { unregisterReceiver(statusRx) } catch (e: Exception) { }
        try { netCb?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (e: Exception) { }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!hasFine()) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L).setMinUpdateIntervalMillis(15_000L).build()
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: Exception) { }
    }

    private fun runNow() { if (!running) return; try { exec.execute { beat(false) } } catch (e: Exception) { } }

    private fun beat(reschedule: Boolean = true) {
        try { doBeat() } catch (e: Exception) { } finally { if (reschedule && running) { try { exec.schedule({ beat() }, intervalS, TimeUnit.SECONDS) } catch (e: Exception) { } } }
    }

    private fun doBeat() {
        if (!Prefs.linked(this)) { stopSelf(); return }
        var loc = lastLoc
        if (loc == null || System.currentTimeMillis() - loc.time > 3 * 60_000L) loc = fix(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 12) ?: loc
        val body = status()
        if (loc != null) { lastLoc = loc; putPos(body, loc) }
        body.put("client_time", System.currentTimeMillis())
        send(body)
    }

    private fun putPos(b: JSONObject, l: Location) {
        b.put("lat", l.latitude); b.put("lng", l.longitude)
        if (l.hasAccuracy()) b.put("accuracy", l.accuracy.toDouble())
        if (l.hasSpeed()) b.put("speed", l.speed.toDouble())
        @Suppress("DEPRECATION")
        val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        if (mock) b.put("mock", true)
    }

    /** older queued heartbeats first (so the newest position stays the newest), then this one */
    private fun send(body: JSONObject) {
        while (queue.isNotEmpty()) {
            val q = queue.first()
            val rr = try { Api.post("live_location.php", q) } catch (e: Exception) { break }
            if (rr.code == 401) { unlink(); return }
            if (rr.code in 200..299 || rr.code == 400) queue.removeFirst() else break
        }
        val r = try { Api.post("live_location.php", body) } catch (e: Exception) {
            if (body.has("lat")) { queue.addLast(body); while (queue.size > 60) queue.removeFirst() }
            return
        }
        if (r.code == 401) { unlink(); return }
        val j = r.json ?: return
        intervalS = j.optLong("interval", 60L).coerceIn(10L, 300L)
        if (j.optBoolean("locate")) {
            // the dashboard asked "where is this phone right now": take a fresh high-accuracy fix and answer at once
            val f = fix(Priority.PRIORITY_HIGH_ACCURACY, 25)
            val b = status(); b.put("ack_locate", 1); b.put("client_time", System.currentTimeMillis())
            if (f != null) { lastLoc = f; putPos(b, f) } else lastLoc?.let { putPos(b, it) }
            try { Api.post("live_location.php", b) } catch (e: Exception) { }
        }
    }

    private fun unlink() { Prefs.clear(this); Geo.register(this); stopSelf() }

    @SuppressLint("MissingPermission")
    private fun fix(priority: Int, secs: Long): Location? {
        if (!hasFine()) return null
        return try { Tasks.await(fused.getCurrentLocation(priority, CancellationTokenSource().token), secs, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun status(): JSONObject {
        val j = JSONObject().put("token", Prefs.token(this))
        try {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                val lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (lv >= 0 && sc > 0) j.put("battery", lv * 100 / sc)
                j.put("charging", if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) 1 else 0)
            }
        } catch (e: Exception) { }
        j.put("net", netType())
        j.put("gps", if (gpsOn()) 1 else 0)
        j.put("perm", perm())
        j.put("saver", if ((getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode) 1 else 0)
        j.put("queued", queue.size)
        j.put("model", (Build.MANUFACTURER + " " + Build.MODEL).take(60))
        j.put("os", Build.VERSION.RELEASE ?: "")
        j.put("app", BuildConfig.VERSION_NAME)
        return j
    }

    private fun netType(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return "none"
            val c = cm.getNetworkCapabilities(nw) ?: return "none"
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi" else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "mobile" else "none"
        } catch (e: Exception) { "none" }
    }

    private fun gpsOn(): Boolean = try { LocationManagerCompat.isLocationEnabled(getSystemService(LOCATION_SERVICE) as LocationManager) } catch (e: Exception) { true }

    private fun perm(): String {
        if (!hasFine()) return "denied"
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return "foreground"
        return "always"
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("tracking", "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("Dipsons attendance")
            .setContentText("Sharing your location with your employer for attendance").setOngoing(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    companion object {
        private const val NOTIF_ID = 4711
        @Volatile var alive = false
        /** start (or keep) the service; does nothing unless the phone is linked and location is allowed */
        fun start(c: Context) {
            if (!Prefs.linked(c)) return
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            try { ContextCompat.startForegroundService(c, Intent(c, TrackerService::class.java)) } catch (e: Exception) { }
        }
        fun stop(c: Context) { try { c.stopService(Intent(c, TrackerService::class.java)) } catch (e: Exception) { } }
    }
}
