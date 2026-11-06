package com.dipsons.autopunch

import android.content.Context

/** Small settings store: link token, employee name, cached company locations (JSON) and the last result shown on screen. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("autopunch", Context.MODE_PRIVATE)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun empName(c: Context): String = sp(c).getString("emp", "") ?: ""
    fun locations(c: Context): String = sp(c).getString("locs", "[]") ?: "[]"
    fun last(c: Context): String = sp(c).getString("last", "") ?: ""
    fun linked(c: Context) = token(c).isNotEmpty()
    fun saveLink(c: Context, token: String, name: String) = sp(c).edit().putString("token", token).putString("emp", name).apply()
    fun saveLocations(c: Context, json: String) = sp(c).edit().putString("locs", json).apply()
    fun saveLast(c: Context, text: String) = sp(c).edit().putString("last", text).apply()
    fun clear(c: Context) = sp(c).edit().clear().apply()
}
