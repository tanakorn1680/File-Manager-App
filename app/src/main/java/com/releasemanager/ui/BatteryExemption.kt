package com.releasemanager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android throttles the network (Doze, App Standby, OEM battery savers) of an app that is not on screen, which is
 * what makes a download crawl once the app is left. "Not optimised" lifts that for this app. The system dialog is
 * shown once, with the first transfer; if the user says no, it is not asked again.
 */
object BatteryExemption {

    private const val PREFS = "transfer_prefs"
    private const val KEY_ASKED = "battery_exemption_asked"

    fun askOnce(activity: Activity) {
        try {
            val pm = activity.getSystemService(PowerManager::class.java) ?: return
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ASKED, false)) return
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Exception) {
            // A device without that settings screen: transfers still work, only without the exemption
        }
    }
}
