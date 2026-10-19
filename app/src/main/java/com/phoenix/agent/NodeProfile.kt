package com.phoenix.agent

/** Stable identity of this node. */
interface NodeIdentity {
    val deviceId: String
}

/** Hardware facts and live memory figures reported to Phoenix Core. */
interface NodeTelemetry {
    val displayName: String
    val architecture: String
    val cpuCores: Int
    val totalRamMb: Long
    val availableRamMb: Long
}
