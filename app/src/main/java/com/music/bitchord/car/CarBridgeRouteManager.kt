package com.music.bitchord.car

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Best-effort local-domain routing for Tesla browser.
 *
 * Tesla blocks RFC1918/private IPs in browser. Local Domain mode advertises a
 * public-looking host/IP (e.g. bitchord.car -> 172.32.0.1) and, when embedded
 * ADB is ready, installs iptables rules that redirect hotspot-client traffic for
 * that IP:PORT to the phone's local bridge port.
 *
 * DNS mapping is handled separately by [CarBridgeDnsService] (VpnService local DNS).
 * This is intentionally isolated and optional. Direct IP mode remains default.
 */
object CarBridgeRouteManager {

    data class State(
        val applied: Boolean = false,
        val message: String = "Direct IP mode",
    )

    private const val TAG = "CarBridgeRoute"
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun apply(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!CarBridgeController.isEnabled(app)) {
            clear(app)
            return@withContext
        }
        if (CarBridgeAccess.mode(app) != CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            clear(app)
            _state.value = State(false, "Direct IP mode")
            return@withContext
        }
        if (!CarBridgeAdb.isReady()) {
            _state.value = State(false, "Tesla Local Domain selected; complete Wireless debugging pairing in settings")
            return@withContext
        }

        val ip = CarBridgeAccess.publicIp(app)
        val port = CarBridgeService.PORT
        val cmds = buildCommands(ip, port)
        val result = runCommands(cmds)
        _state.value = if (result.ok) {
            State(true, "Local domain routing active for $ip:$port")
        } else {
            State(false, result.message)
        }
        Log.i(TAG, _state.value.message)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val ip = CarBridgeAccess.publicIp(app)
        val port = CarBridgeService.PORT
        if (CarBridgeAdb.isReady()) {
            runCommands(buildCleanupCommands(ip, port))
        }
        _state.value = State(false, "Direct IP mode")
    }

    private fun buildCommands(ip: String, port: Int): List<String> {
        val safeIp = ip.replace(Regex("[^0-9.]"), "")
        return listOf(
            "iptables -t nat -D PREROUTING -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port || true",
            "iptables -t nat -I PREROUTING 1 -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port",
            "iptables -D FORWARD -p tcp -d $safeIp --dport $port -j ACCEPT || true",
            "iptables -I FORWARD 1 -p tcp -d $safeIp --dport $port -j ACCEPT",
            "iptables -D OUTPUT -t nat -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port || true",
            "iptables -I OUTPUT 1 -t nat -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port",
        )
    }

    private fun buildCleanupCommands(ip: String, port: Int): List<String> {
        val safeIp = ip.replace(Regex("[^0-9.]"), "")
        return listOf(
            "iptables -t nat -D PREROUTING -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port || true",
            "iptables -D FORWARD -p tcp -d $safeIp --dport $port -j ACCEPT || true",
            "iptables -t nat -D OUTPUT -p tcp -d $safeIp --dport $port -j REDIRECT --to-ports $port || true",
        )
    }

    private data class CmdResult(val ok: Boolean, val message: String)

    private suspend fun runCommands(commands: List<String>): CmdResult {
        var lastErr = ""
        for (cmd in commands) {
            val r = runShell(cmd)
            if (!r.ok) lastErr = r.message
        }
        return if (lastErr.isBlank()) CmdResult(true, "ok") else CmdResult(false, lastErr)
    }

    private suspend fun runShell(command: String): CmdResult {
        return CarBridgeAdb.shell(command).fold(
            onSuccess = { out -> CmdResult(true, out.ifBlank { "ok" }) },
            onFailure = { e -> CmdResult(false, e.message ?: "adb shell failed") }
        )
    }
}
