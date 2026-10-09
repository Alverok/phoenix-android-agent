package com.phoenix.agent

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("phoenix_agent", Context.MODE_PRIVATE)
    val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: ("android-" + UUID.randomUUID()).also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}