package com.phoenix.agent

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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

    private val eventLines =
        ArrayDeque<String>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        bindViews()

        val telemetry =
            DeviceTelemetry(this)

        val identity =
            DeviceIdentity(this)

        agent =
            PhoenixNodeAgent(
                identity = identity,
                telemetry = telemetry,
                http = PhoenixHttpClient()
            ) { message ->
                runOnUiThread {
                    handleAgentMessage(message)
                }
            }

        renderDevice(
            identity,
            telemetry
        )

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

    private fun bindViews() {

        statusText =
            findViewById(R.id.statusText)

        deviceNameText =
            findViewById(R.id.deviceNameText)

        deviceInfoText =
            findViewById(R.id.deviceInfoText)

        deviceIdText =
            findViewById(R.id.deviceIdText)

        cardCores =
            findViewById(R.id.cardCores)

        cardRam =
            findViewById(R.id.cardRam)

        coreUrlInput =
            findViewById(R.id.coreUrlInput)

        connectButton =
            findViewById(R.id.connectButton)

        taskStateText =
            findViewById(R.id.taskStateText)

        taskDetailText =
            findViewById(R.id.taskDetailText)

        logText =
            findViewById(R.id.logText)
    }

    private fun renderDevice(
        identity: DeviceIdentity,
        telemetry: DeviceTelemetry
    ) {

        deviceNameText.text =
            telemetry.displayName

        deviceInfoText.text =
            "Android ${android.os.Build.VERSION.RELEASE}  •  " +
                    "API ${android.os.Build.VERSION.SDK_INT}\n" +
                    "${telemetry.architecture}  •  " +
                    "${telemetry.cpuCores} CPU cores"

        deviceIdText.text =
            "Node identity\n${identity.deviceId}"

        cardCores.text =
            telemetry.cpuCores.toString()

        cardRam.text =
            "${telemetry.totalRamMb} MB"

        coreUrlInput.setText(
            getPreferences(
                MODE_PRIVATE
            ).getString(
                "core_url",
                ""
            ) ?: ""
        )
    }

    private fun connectToPhoenix() {

        val url =
            coreUrlInput.text
                .toString()
                .trim()
                .removeSuffix("/")

        if (url.isBlank()) {

            appendEvent(
                "Enter a Phoenix Core address first."
            )

            taskStateText.text =
                "CORE ADDRESS REQUIRED"

            taskDetailText.text =
                "Example: http://192.168.1.10:8080"

            return
        }

        getPreferences(
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "core_url",
                url
            )
            .apply()

        setConnectingState()

        taskStateText.text =
            "CONNECTING"

        taskDetailText.text =
            "Registering this device with Phoenix Core..."

        appendEvent(
            "Connecting to $url"
        )

        agent.connect(
            coreUrl = url,

            onConnected = {

                runOnUiThread {
                    setConnectedState()

                    taskStateText.text =
                        "READY FOR TASKS"

                    taskDetailText.text =
                        "Waiting for Phoenix Core to dispatch work."

                    appendEvent(
                        "Registered with Phoenix Core."
                    )
                }
            },

            onFailed = { error ->

                runOnUiThread {

                    setFailedState()

                    taskStateText.text =
                        "CONNECTION FAILED"

                    taskDetailText.text =
                        error

                    appendEvent(
                        error
                    )
                }
            }
        )
    }

    private fun handleAgentMessage(
        message: String
    ) {

        appendEvent(message)

        when {

            message.startsWith(
                "Registered with Phoenix."
            ) -> {

                setConnectedState()

                taskStateText.text =
                    "READY FOR TASKS"

                taskDetailText.text =
                    "Phoenix Core can dispatch work to this node."
            }

            message.startsWith(
                "Heartbeat sent:"
            ) -> {

                if (agent.running) {
                    setConnectedState()
                }
            }

            message.startsWith(
                "Received task "
            ) -> {

                val taskId =
                    extractTaskIdFromReceivedMessage(
                        message
                    )

                setConnectedState()

                taskStateText.text =
                    "EXECUTING"

                taskDetailText.text =
                    if (taskId != null) {
                        "$taskId  •  Monte-Carlo workload"
                    } else {
                        "Phoenix task execution in progress."
                    }
            }

            message.startsWith(
                "Task "
            ) &&
                    message.contains(
                        " done:"
                    ) -> {

                setConnectedState()

                taskStateText.text =
                    "TASK COMPLETE"

                taskDetailText.text =
                    "Execution completed successfully. Ready for more work."
            }

            message.startsWith(
                "Poll failed:"
            ) -> {

                taskStateText.text =
                    "TASK CHANNEL WARNING"

                taskDetailText.text =
                    message.removePrefix(
                        "Poll failed:"
                    ).trim()
            }

            message.startsWith(
                "Heartbeat failed:"
            ) -> {

                taskDetailText.text =
                    "Heartbeat could not reach Phoenix Core."

                if (agent.running) {
                    setWarningState()
                }
            }

            message.startsWith(
                "Agent stopped."
            ) -> {

                setDisconnectedState()

                taskStateText.text =
                    "READY"

                taskDetailText.text =
                    "Connect to Phoenix Core to rejoin the mesh."
            }
        }
    }

    private fun extractTaskIdFromReceivedMessage(
        message: String
    ): String? {

        val prefix =
            "Received task "

        if (!message.startsWith(prefix)) {
            return null
        }

        return message
            .removePrefix(prefix)
            .substringBefore(",")
            .trim()
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun setConnectingState() {

        statusText.text =
            "CONNECTING"

        statusText.setTextColor(
            Color.rgb(
                255,
                210,
                105
            )
        )

        connectButton.text =
            "CONNECTING…"

        connectButton.isEnabled =
            false
    }

    private fun setConnectedState() {

        statusText.text =
            "● ONLINE"

        statusText.setTextColor(
            Color.rgb(
                106,
                233,
                165
            )
        )

        connectButton.text =
            "DISCONNECT"

        connectButton.isEnabled =
            true
    }

    private fun setWarningState() {

        statusText.text =
            "● DEGRADED"

        statusText.setTextColor(
            Color.rgb(
                255,
                195,
                92
            )

        )

        connectButton.text =
            "DISCONNECT"

        connectButton.isEnabled =
            true
    }

    private fun setFailedState() {

        statusText.text =
            "● ERROR"

        statusText.setTextColor(
            Color.rgb(
                255,
                115,
                115
            )
        )

        connectButton.text =
            "CONNECT TO PHOENIX"

        connectButton.isEnabled =
            true
    }

    private fun setDisconnectedState() {

        statusText.text =
            "● OFFLINE"

        statusText.setTextColor(
            Color.rgb(
                150,
                153,
                170
            )
        )

        connectButton.text =
            "CONNECT TO PHOENIX"

        connectButton.isEnabled =
            true

        taskStateText.text =
            "NOT CONNECTED"

        taskDetailText.text =
            "Connect to Phoenix Core to join the edge mesh."
    }

    private fun appendEvent(
        message: String
    ) {

        if (message.isBlank()) {
            return
        }

        eventLines.addLast(
            message
        )

        while (eventLines.size > 30) {
            eventLines.removeFirst()
        }

        logText.text =
            eventLines
                .joinToString(
                    separator = "\n"
                )
    }

    override fun onDestroy() {

        if (::agent.isInitialized) {
            agent.disconnect()
        }

        super.onDestroy()
    }
}
