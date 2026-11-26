package com.example.data.engine

import com.example.data.model.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class DaemonLogEntry(
    val timestamp: String,
    val text: String,
    val colorType: String = "GREEN" // GREEN, CYAN, YELLOW, RED
)

data class DaemonState(
    val isRunning: Boolean = true,
    val executionState: String = "[*] MASKING_TRAFFIC",
    val payloadCount: Int = 5,
    val totalPayloadsExecuted: Int = 18,
    val integrityPercent: Int = 100,
    val latencyMs: Int = 47,
    val tunnelStatus: String = "ACTIVE",
    val warpLock: String = "ENFORCED",
    val mode: String = "ROOT_KERNEL",
    val logs: List<DaemonLogEntry> = emptyList()
)

class GhostDaemonEngine(private val scope: CoroutineScope) {

    private val _daemonState = MutableStateFlow(DaemonState())
    val daemonState: StateFlow<DaemonState> = _daemonState.asStateFlow()

    private var workerJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val sampleTerminalLines = listOf(
        "SECURING_SOCKET >> UNITY_ENGINE (PORT: 443)" to "CYAN",
        "INJECTING_DAEMON >> BYPASSING_SDK_CHECKS" to "GREEN",
        "REAP_CLEANED >> AWAITING_NEXT_VECTOR" to "GREEN",
        "SYNCING_THREAD >> T-MINUS 5 SECONDS" to "YELLOW",
        "SYNCING_THREAD >> T-MINUS 2 SECONDS" to "YELLOW",
        "INIT_SPOOF >> TARGET=INTERSTITIAL" to "CYAN",
        "AD_NETWORK_SHIELD: BLOCKED_TRACKER (ID: GAID_INTERCEPT)" to "GREEN",
        "DNS_OVER_TLS >> TUNNEL_PROXY_ESTABLISHED" to "GREEN",
        "WEBVIEW_HASH_REPLACED >> CLEAN_FINGERPRINT" to "CYAN",
        "THREAD_JOINED >> SIGHUP IGNORED" to "GREEN",
        "EXIT_CODE=CLOSED_BY_TARGET (0x0)" to "YELLOW",
        "WARP_CORE >> PACKET_MASKING_OK (CRC32: 0x9F41)" to "GREEN",
        "FINGERPRINT_VERIFY >> PASSED_100_PERCENT" to "GREEN"
    )

    init {
        startDaemon()
    }

    fun startDaemon() {
        if (workerJob?.isActive == true) return
        _daemonState.value = _daemonState.value.copy(isRunning = true, executionState = "[*] MASKING_TRAFFIC")

        workerJob = scope.launch(Dispatchers.Default) {
            // Initial seed logs
            val initialLogs = mutableListOf<DaemonLogEntry>()
            for (i in 0 until 8) {
                val line = sampleTerminalLines[i % sampleTerminalLines.size]
                initialLogs.add(
                    DaemonLogEntry(
                        timestamp = timeFormat.format(Date(System.currentTimeMillis() - (8 - i) * 2000L)),
                        text = "[+] ${line.first}",
                        colorType = line.second
                    )
                )
            }
            _daemonState.value = _daemonState.value.copy(logs = initialLogs)

            var count = 5
            while (isActive) {
                delay(Random.nextLong(2000L, 4500L))
                val randomLine = sampleTerminalLines.random()
                val newLog = DaemonLogEntry(
                    timestamp = timeFormat.format(Date()),
                    text = "[+] ${randomLine.first}",
                    colorType = randomLine.second
                )
                val currentLogs = _daemonState.value.logs.takeLast(40).toMutableList()
                currentLogs.add(newLog)

                count++
                val newPayloadCount = if (count % 4 == 0) Random.nextInt(1, 6) else _daemonState.value.payloadCount
                val newLatency = Random.nextInt(32, 68)

                _daemonState.value = _daemonState.value.copy(
                    logs = currentLogs,
                    payloadCount = newPayloadCount,
                    totalPayloadsExecuted = _daemonState.value.totalPayloadsExecuted + 1,
                    latencyMs = newLatency
                )
            }
        }
    }

    fun toggleDaemon() {
        if (_daemonState.value.isRunning) {
            workerJob?.cancel()
            _daemonState.value = _daemonState.value.copy(
                isRunning = false,
                executionState = "[!] IDLE / PAUSED",
                tunnelStatus = "STANDBY"
            )
        } else {
            startDaemon()
        }
    }

    fun addManualLog(message: String, type: String = "CYAN") {
        val entry = DaemonLogEntry(
            timestamp = timeFormat.format(Date()),
            text = "[*] $message",
            colorType = type
        )
        val currentLogs = _daemonState.value.logs.takeLast(40).toMutableList()
        currentLogs.add(entry)
        _daemonState.value = _daemonState.value.copy(logs = currentLogs)
    }
}
