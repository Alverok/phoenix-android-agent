package com.phoenix.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Build

class DeviceTelemetry(private val context: Context) {
    private fun memInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }
    val architecture get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    val cpuCores get() = Runtime.getRuntime().availableProcessors()
    val totalRamMb get() = memInfo().totalMem / 1024 / 1024
    val availableRamMb get() = memInfo().availMem / 1024 / 1024
    val displayName get() = "${Build.MANUFACTURER}-${Build.MODEL}"
}