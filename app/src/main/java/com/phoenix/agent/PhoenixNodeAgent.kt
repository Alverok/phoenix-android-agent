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

    // Protocol features announced to Phoenix Core at registration.
    //
    // "attempt-id": this agent echoes the opaque attemptId Core sends with each
    // task, verbatim, on /tasks/started and /results. Core uses that to reject
    // a late or duplicate report from an earlier attempt of the same task.
    // Without it Core registers the node as LEGACY (unfenced).
    //
    // Only announce what this class really implements: Core starts sending
    // attemptId/attemptNumber to this node, and *requires* the echo (a report
    // without it is rejected), as soon as the feature is announced.
    private val protocolFeatures = listOf("attempt-id")

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
                    .put("protocolFeatures", org.json.JSONArray(protocolFeatures))
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

            // Attempt identity. Present only when Core treats this node as
            // fenced; null for an older Core that sends none. It is echoed back
            // unchanged and never interpreted. attemptNumber is for logs only.
            // (opt + "as? String" rather than optString: on Android a JSON null
            // would come back as the text "null".)
            val attemptId = task.opt("attemptId") as? String
            val attemptNumber = task.optLong("attemptNumber", -1)

            // The text up to the first comma must stay "Received task <id>":
            // MainActivity parses the task id out of this line.
            onLog(
                "Received task $taskId, executing Monte-Carlo (samples=$samples" +
                    (if (attemptId != null)
                        ", attempt $attemptNumber, id ${shortId(attemptId)}"
                    else "") +
                ")..."
            )

            // Optional "execution began" signal. Best effort: Core works
            // without it, so a failure here must never abort the task.
            try {
                val started = JSONObject().put("taskId", taskId)
                if (attemptId != null) started.put("attemptId", attemptId)
                http.postJson("$coreUrl/tasks/started", started.toString())
            } catch (e: Exception) {
                onLog("Start notification failed (continuing): ${e.message}")
            }

            val result = executor.execute(samples)
            val resultBody = JSONObject()
                .put("taskId", taskId)
                .put("status", "SUCCESS")
                .put("outputData", result.output)
                .put("executionTimeMs", result.executionTimeMs)
            if (attemptId != null) resultBody.put("attemptId", attemptId)
            http.postJson("$coreUrl/results", resultBody.toString())
            onLog("Task $taskId done: ${result.output} (${result.executionTimeMs}ms)")
        } catch (e: Exception) {
            onLog("Poll failed: ${e.message}")
        } finally {
            busy.set(false)
        }
    }

    /** First 8 characters, for log lines only. */
    private fun shortId(id: String) = if (id.length <= 8) id else id.substring(0, 8)
}
