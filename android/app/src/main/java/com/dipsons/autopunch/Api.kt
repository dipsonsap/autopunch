package com.dipsons.autopunch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thin JSON-over-HTTPS client for the three API files. All calls are blocking: call them from a worker / background thread. */
object Api {
    class Reply(val code: Int, val json: JSONObject?)

    fun post(endpoint: String, body: JSONObject): Reply {
        val conn = URL(BuildConfig.API_BASE + endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            return Reply(code, json)
        } finally {
            conn.disconnect()
        }
    }
}
