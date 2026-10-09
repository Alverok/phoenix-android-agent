package com.phoenix.agent

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhoenixNodeAgent(
    private val identity: DeviceIdentity,
    private val telemetry: DeviceTelemetry,
    private val http: PhoenixHttpClient,
    private val onLog: (String) -> Unit
) {
    private var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val executor = MonteCarloExecutor()
    var running = false; private set

    // Real signal for loadPenalty: true while a task is actually executing,
    // not a hardcoded 0 that lied to the scheduler regardless of state.
    private val busy = AtomicBoolean(false)

    // Heuristic, not measured: mobile hardware on battery costs more energy
    // per task than a plugged-in Linux box (WeightedPlacementCostModel's own
    // Javadoc already calls real per-hardware energy profiling future work —
    // this constant is the honest placeholder that fills the same slot Linux
    // fills with 4.5, just reflecting a different hardware class's profile).
    private val energyCostEstimate = 6.0

    // RTT measured from the previous heartbeat response and reported
    // in the following heartbeat, matching the Linux agent semantics.
    private var lastHeartbeatRttMs = 0.0

    fun connect(coreUrl: String, onConnected: () -> Unit, onFailed: (String) -> Unit) {
        running = true
        Thread {
            try {
                val payload = JSONObject()
                    .put("deviceId", identity.deviceId)
                    .put("displayName", telemetry.displayName)
                    .put("hardwareClass", "ANDROID")
                    .put("architecture", telemetry.architecture)
                    .put("cpuCores", telemetry.cpuCores)
                    .put("memoryMb", telemetry.totalRamMb)
                    .put("adapterType", "android")
                    .put("capabilities", org.json.JSONArray())
                http.postJson("$coreUrl/register", payload.toString())
                onLog("Registered with Phoenix.")
                onConnected()

                scheduler.scheduleAtFixedRate({ if (running) heartbeat(coreUrl) }, 0, 5, TimeUnit.SECONDS)
                scheduler.scheduleAtFixedRate({ if (running) pollForTask(coreUrl) }, 1, 3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                running = false
                onFailed(e.message ?: "unknown error")
            }
        }.start()
    }

    fun disconnect() {
        running = false
        scheduler.shutdownNow()
        onLog("Agent stopped.")
        scheduler = Executors.newScheduledThreadPool(2)
    }

    private fun heartbeat(coreUrl: String) {
        try {
            val body = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("cpuUsagePercent", -1.0) // still honestly "not measurable" on this platform
                .put("availableRamMb", telemetry.availableRamMb)
                .put("activeTasks", if (busy.get()) 1 else 0)
                .put("queueLength", 0)
                .put("networkLatencyMs", lastHeartbeatRttMs)
                .put("energyCostEstimate", energyCostEstimate)

            val start = System.nanoTime()
            http.postJson("$coreUrl/heartbeat", body.toString())
            val rttMs = (System.nanoTime() - start) / 1_000_000.0

            lastHeartbeatRttMs = rttMs
            onLog("Heartbeat sent: RAM=${telemetry.availableRamMb}MB RTT=${"%.1f".format(rttMs)}ms")
        } catch (e: Exception) {
            onLog("Heartbeat failed: ${e.message}")
        }
    }

    private fun pollForTask(coreUrl: String) {
        if (!running || !busy.compareAndSet(false, true)) return

        try {
            val (code, body) = http.get("$coreUrl/tasks/poll?deviceId=${identity.deviceId}")
            if (code != 200 || body.isBlank()) return

            val task = JSONObject(body)
            val taskId = task.optString("taskId", task.optString("id"))
            if (taskId.isBlank()) {
                throw IllegalStateException("Task response missing task id: $body")
            }
            val samples = task.optLong("samples", 2_000_000)
            onLog("Received task $taskId, executing Monte-Carlo (samples=$samples)...")

            val result = executor.execute(samples)
            val resultBody = JSONObject()
                .put("taskId", taskId)
                .put("status", "SUCCESS")
                .put("outputData", result.output)
                .put("executionTimeMs", result.executionTimeMs)
            http.postJson("$coreUrl/results", resultBody.toString())
            onLog("Task $taskId done: ${result.output} (${result.executionTimeMs}ms)")
        } catch (e: Exception) {
            onLog("Poll failed: ${e.message}")
        } finally {
            busy.set(false)
        }
    }
}
