package com.phoenix.agent

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Single-screen UI for the Phoenix node agent. */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREF_CORE_URL = "core_url"
        const val MAX_EVENT_LINES = 30

        val COLOR_ONLINE = Color.rgb(106, 233, 165)
        val COLOR_CONNECTING = Color.rgb(255, 210, 105)
        val COLOR_DEGRADED = Color.rgb(255, 195, 92)
        val COLOR_ERROR = Color.rgb(255, 115, 115)
        val COLOR_OFFLINE = Color.rgb(150, 153, 170)
    }

    private lateinit var statusText: TextView
    private lateinit var deviceNameText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var cardCores: TextView
    private lateinit var cardRam: TextView
    private lateinit var coreUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var taskStateText: TextView
    private lateinit var taskDetailText: TextView
    private lateinit var logText: TextView

    private lateinit var agent: PhoenixNodeAgent

    private val eventLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        val telemetry = DeviceTelemetry(this)
        val identity = DeviceIdentity(this)

        agent = PhoenixNodeAgent(identity, telemetry, PhoenixHttpClient()) { message ->
            runOnUiThread { handleAgentMessage(message) }
        }

        renderDevice(identity, telemetry)
        setDisconnectedState()

        connectButton.setOnClickListener {
            if (agent.running) {
                agent.disconnect()
                setDisconnectedState()
            } else {
                connectToPhoenix()
            }
        }
    }

    override fun onDestroy() {
        if (::agent.isInitialized) {
            agent.disconnect()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        deviceNameText = findViewById(R.id.deviceNameText)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        deviceIdText = findViewById(R.id.deviceIdText)
        cardCores = findViewById(R.id.cardCores)
        cardRam = findViewById(R.id.cardRam)
        coreUrlInput = findViewById(R.id.coreUrlInput)
        connectButton = findViewById(R.id.connectButton)
        taskStateText = findViewById(R.id.taskStateText)
        taskDetailText = findViewById(R.id.taskDetailText)
        logText = findViewById(R.id.logText)
    }

    private fun renderDevice(identity: DeviceIdentity, telemetry: DeviceTelemetry) {
        deviceNameText.text = telemetry.displayName
        deviceInfoText.text =
            "Android ${Build.VERSION.RELEASE}  •  API ${Build.VERSION.SDK_INT}\n" +
                "${telemetry.architecture}  •  ${telemetry.cpuCores} CPU cores"
        deviceIdText.text = "Node identity\n${identity.deviceId}"
        cardCores.text = telemetry.cpuCores.toString()
        cardRam.text = "${telemetry.totalRamMb} MB"
        coreUrlInput.setText(getPreferences(MODE_PRIVATE).getString(PREF_CORE_URL, "") ?: "")
    }

    private fun connectToPhoenix() {
        val url = coreUrlInput.text.toString().trim().removeSuffix("/")

        if (url.isBlank()) {
            appendEvent("Enter a Phoenix Core address first.")
            showTask("CORE ADDRESS REQUIRED", "Example: http://192.168.1.10:8080")
            return
        }

        getPreferences(MODE_PRIVATE).edit().putString(PREF_CORE_URL, url).apply()

        setConnectingState()
        showTask("CONNECTING", "Registering this device with Phoenix Core...")
        appendEvent("Connecting to $url")

        agent.connect(
            coreUrl = url,
            // The agent logs "Registered with Phoenix.", which updates the UI.
            onConnected = {},
            onFailed = { error ->
                runOnUiThread {
                    setFailedState()
                    showTask("CONNECTION FAILED", error)
                    appendEvent(error)
                }
            }
        )
    }

    private fun handleAgentMessage(message: String) {
        appendEvent(message)

        when {
            message.startsWith("Registered with Phoenix.") -> {
                setConnectedState()
                showTask("READY FOR TASKS", "Phoenix Core can dispatch work to this node.")
            }

            message.startsWith("Heartbeat sent:") -> {
                if (agent.running) {
                    setConnectedState()
                }
            }

            message.startsWith("Received task ") -> {
                val taskId = message.removePrefix("Received task ").substringBefore(",").trim()
                setConnectedState()
                showTask(
                    "EXECUTING",
                    if (taskId.isNotBlank()) "$taskId  •  Monte-Carlo workload"
                    else "Phoenix task execution in progress."
                )
            }

            message.startsWith("Task ") && message.contains(" done:") -> {
                setConnectedState()
                showTask("TASK COMPLETE", "Execution completed successfully. Ready for more work.")
            }

            message.startsWith("Task ") && message.contains(" failed:") -> {
                showTask("TASK FAILED", message.substringAfter(" failed:").trim())
            }

            message.startsWith("Poll failed:") -> {
                showTask("TASK CHANNEL WARNING", message.removePrefix("Poll failed:").trim())
            }

            message.startsWith("Heartbeat failed:") -> {
                taskDetailText.text = "Heartbeat could not reach Phoenix Core."
                if (agent.running) {
                    setWarningState()
                }
            }

            message.startsWith("Agent stopped.") -> {
                setDisconnectedState()
                showTask("READY", "Connect to Phoenix Core to rejoin the mesh.")
            }
        }
    }

    private fun showTask(state: String, detail: String) {
        taskStateText.text = state
        taskDetailText.text = detail
    }

    private fun setStatus(label: String, color: Int, button: String, buttonEnabled: Boolean) {
        statusText.text = label
        statusText.setTextColor(color)
        connectButton.text = button
        connectButton.isEnabled = buttonEnabled
    }

    private fun setConnectingState() =
        setStatus("CONNECTING", COLOR_CONNECTING, "CONNECTING…", buttonEnabled = false)

    private fun setConnectedState() =
        setStatus("● ONLINE", COLOR_ONLINE, "DISCONNECT", buttonEnabled = true)

    private fun setWarningState() =
        setStatus("● DEGRADED", COLOR_DEGRADED, "DISCONNECT", buttonEnabled = true)

    private fun setFailedState() =
        setStatus("● ERROR", COLOR_ERROR, "CONNECT TO PHOENIX", buttonEnabled = true)

    private fun setDisconnectedState() {
        setStatus("● OFFLINE", COLOR_OFFLINE, "CONNECT TO PHOENIX", buttonEnabled = true)
        showTask("NOT CONNECTED", "Connect to Phoenix Core to join the edge mesh.")
    }

    private fun appendEvent(message: String) {
        if (message.isBlank()) {
            return
        }

        eventLines.addLast(message)
        while (eventLines.size > MAX_EVENT_LINES) {
            eventLines.removeFirst()
        }

        logText.text = eventLines.joinToString(separator = "\n")
    }
}
