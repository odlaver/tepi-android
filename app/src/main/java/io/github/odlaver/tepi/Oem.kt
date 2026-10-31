package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

// Brand-specific background limits
object Oem {
    private val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

    private val pages: List<ComponentName> = when {
        listOf("xiaomi", "redmi", "poco").any { it in brand } -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        listOf("oppo", "realme", "oneplus").any { it in brand } -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        )
        listOf("vivo", "iqoo").any { it in brand } -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        listOf("huawei", "honor").any { it in brand } -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        else -> emptyList()
    }

    // Has its own autostart switch
    val hasAutostart get() = pages.isNotEmpty()

    val name: String get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    fun openAutostart(a: Activity) {
        for (c in pages) {
            try { a.startActivity(Intent().setComponent(c)); return } catch (_: Exception) { }
        }
        appInfo(a)
    }

    fun appInfo(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
    }

    fun batteryFree(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    @SuppressLint("BatteryLife")
    fun askBattery(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        } catch (_: Exception) {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
