package com.dipsons.autopunch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/** One screen: link the phone with the code from the dashboard, grant the location permissions, and see the last auto punch. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var last: TextView
    private lateinit var code: EditText
    private lateinit var linkBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        col.addView(label("Dipsons Auto Punch", 24f, true))
        status = label("", 16f); col.addView(status)
        code = EditText(this).apply { hint = "8-digit code from your dashboard"; inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER; textSize = 22f }
        col.addView(code)
        linkBtn = button("Link this phone") { link() }; col.addView(linkBtn)
        col.addView(button("1. Allow location") { askLocation() })
        col.addView(button("2. Allow location 'all the time'") { askBackground() })
        col.addView(button("3. Let the app run in the background") { batterySettings() })
        col.addView(button("Refresh company locations") { refreshNow() })
        col.addView(label("Last auto punch", 14f, true))
        last = label("", 14f); col.addView(last)
        col.addView(label("A notification saying Sharing your location with your employer stays visible while live location sharing is on - please do not swipe the app away from Recent apps or force-stop it. Opening this app once after a restart is not needed. If sharing stops, set Battery to 'Unrestricted' for this app (step 3).", 12f))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        render()
        if (Prefs.linked(this)) { TrackerService.start(this); ConfigWorker.schedule(this); thread { ConfigWorker.refresh(applicationContext); runOnUiThread { render() } } }
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasBg() = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val linked = Prefs.linked(this)
        status.text = if (!linked) "Not linked. Open the dashboard > Auto punch, get a code and enter it here."
        else "Linked as ${Prefs.empName(this)}\nLocation: ${if (hasFine()) "allowed" else "NOT allowed"} / all the time: ${if (hasBg()) "allowed" else "NOT allowed"}\nLive location sharing: ${if (TrackerService.alive) "running" else "starting... (needs location allowed)"}"
        code.visibility = if (linked) android.view.View.GONE else android.view.View.VISIBLE
        linkBtn.visibility = code.visibility
        last.text = Prefs.last(this).ifEmpty { "Nothing yet. Punches happen by themselves when you reach or leave a company location." }
    }

    private fun link() {
        val c = code.text.toString().trim()
        if (c.length != 8) { toast("Enter the 8-digit code"); return }
        linkBtn.isEnabled = false
        thread {
            val name = (Build.MANUFACTURER + " " + Build.MODEL).take(70)
            var msg = ""
            try {
                val r = Api.post("autopunch_pair.php", JSONObject().put("code", c).put("device_name", name))
                val j = r.json
                if (j != null && j.optBoolean("ok")) {
                    Prefs.saveLink(applicationContext, j.getString("token"), j.optString("emp_name"))
                    ConfigWorker.refresh(applicationContext)
                    ConfigWorker.schedule(applicationContext)
                    msg = "Linked"
                } else msg = j?.optString("msg") ?: "Server error (${r.code})"
            } catch (e: Exception) { msg = "No connection. Check the internet and try again." }
            runOnUiThread { linkBtn.isEnabled = true; toast(msg); render(); TrackerService.start(this); if (Prefs.linked(this) && !hasFine()) askLocation() }
        }
    }

    private fun askLocation() {
        val p = ArrayList<String>().apply { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
        requestPermissions(p.toTypedArray(), 11)
    }

    private fun askBackground() {
        if (!hasFine()) { toast("Do step 1 first"); return }
        if (Build.VERSION.SDK_INT >= 30) {
            toast("On the next screen choose Location > 'Allow all the time'")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 12)
    }

    private fun batterySettings() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun refreshNow() {
        if (!Prefs.linked(this)) { toast("Link the phone first"); return }
        thread { val ok = ConfigWorker.refresh(applicationContext); runOnUiThread { toast(if (ok) "Locations updated" else "Could not reach the server"); render() } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Geo.register(this); TrackerService.start(this); render()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
