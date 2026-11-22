package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.car.CarBridgeAccess
import com.music.bitchord.car.CarBridgeAccessMode
import com.music.bitchord.car.CarBridgeAdb
import com.music.bitchord.car.CarBridgeController
import com.music.bitchord.car.CarBridgeDnsService
import com.music.bitchord.car.CarBridgeRouteManager
import com.music.bitchord.car.CarBridgeService

/**
 * Settings entry for Tesla/car web remote.
 *
 * Local Domain mode is BitChord-native: embedded ADB (Kadb) + local DNS VPN.
 * No external Shizuku app required.
 */
@Composable
internal fun CarBridgeSettingsRow() {
    val context = LocalContext.current
    val enabled by CarBridgeController.enabled.collectAsStateWithLifecycle()
    val mode by CarBridgeController.accessMode.collectAsStateWithLifecycle()
    val adb by CarBridgeAdb.state.collectAsStateWithLifecycle()
    val route by CarBridgeRouteManager.state.collectAsStateWithLifecycle()

    var domain by remember { mutableStateOf(CarBridgeAccess.localDomain(context)) }
    var publicIp by remember { mutableStateOf(CarBridgeAccess.publicIp(context)) }
    var connectPort by remember { mutableStateOf(CarBridgeAdb.savedConnectPort(context)?.toString().orEmpty()) }
    var pairPort by remember { mutableStateOf(CarBridgeAdb.savedPairPort(context)?.toString().orEmpty()) }
    var pairingCode by remember { mutableStateOf("") }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CarBridgeDnsService.start(context)
            CarBridgeController.refreshRunningState(context)
        }
    }

    LaunchedEffect(Unit) {
        CarBridgeController.load(context)
        domain = CarBridgeAccess.localDomain(context)
        publicIp = CarBridgeAccess.publicIp(context)
        connectPort = CarBridgeAdb.savedConnectPort(context)?.toString().orEmpty()
        pairPort = CarBridgeAdb.savedPairPort(context)?.toString().orEmpty()
    }

    SettingsGroup(header = "Car Bridge") {
        SettingsRow(
            icon = Icons.Rounded.DirectionsCar,
            title = "Tesla Car Bridge",
            subtitle = if (enabled) CarBridgeController.accessUrl(context) else "Off. Playback stays on the phone; browser is a remote UI.",
            trailing = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { CarBridgeController.setEnabled(context, it) },
                )
            },
        )

        RowDivider()

        SettingsRow(
            title = "Access mode",
            subtitle = when (mode) {
                CarBridgeAccessMode.DIRECT_IP -> "Direct hotspot IP (simple, but Tesla browser may block private IPs)"
                CarBridgeAccessMode.TESLA_LOCAL_DOMAIN -> "Built-in Local Domain mode (embedded ADB + local DNS). No Shizuku app."
            },
            trailing = {
                TextButton(onClick = {
                    val next = if (mode == CarBridgeAccessMode.DIRECT_IP) {
                        CarBridgeAccessMode.TESLA_LOCAL_DOMAIN
                    } else {
                        CarBridgeAccessMode.DIRECT_IP
                    }
                    CarBridgeController.setAccessMode(context, next)
                }) {
                    Text(if (mode == CarBridgeAccessMode.DIRECT_IP) "Use Domain" else "Use IP")
                }
            },
        )

        if (mode == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 8.dp)) {
                Text(
                    "Tesla blocks private IPs. This mode makes BitChord serve a public-looking domain/IP locally. It uses Android Wireless Debugging (ADB) inside BitChord for iptables redirect, plus a local VPN DNS helper. First-time wireless debugging pairing is required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; CarBridgeAccess.setLocalDomain(context, it) },
                    label = { Text("Local domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = publicIp,
                    onValueChange = { publicIp = it; CarBridgeAccess.setPublicIp(context, it) },
                    label = { Text("Public-looking IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Wireless debugging port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairPort,
                    onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Pairing port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("ADB: ${adb.status} - ${adb.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Route: ${if (route.applied) "active" else "inactive"} - ${route.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("DNS VPN: ${if (CarBridgeDnsService.running) "running" else "stopped"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))

                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Developer options") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val opened = runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }.isSuccess
                        if (!opened) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Wireless debugging") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { CarBridgeAdb.startDiscovery(context) }) { Text("Discover") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        CarBridgeAdb.setManualEndpoint(context, "127.0.0.1", connectPort.toIntOrNull(), pairPort.toIntOrNull())
                        CarBridgeAdb.pair(context, pairingCode, pairPort.toIntOrNull())
                    }) { Text("Pair") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        CarBridgeAdb.setManualEndpoint(context, "127.0.0.1", connectPort.toIntOrNull(), pairPort.toIntOrNull())
                        CarBridgeAdb.connect(context, connectPort.toIntOrNull())
                    }) { Text("Connect") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        val prep = CarBridgeDnsService.prepareIntent(context)
                        if (prep != null) vpnLauncher.launch(prep) else {
                            CarBridgeDnsService.start(context)
                            CarBridgeController.refreshRunningState(context)
                        }
                    }) { Text("Start DNS VPN") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        CarBridgeDnsService.stopDns(context)
                        CarBridgeController.refreshRunningState(context)
                    }) { Text("Stop DNS VPN") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { CarBridgeController.refreshRunningState(context) }) { Text("Apply/Test") }
                }
                Text(
                    "After pairing+connect+DNS VPN, open http://${domain.ifBlank { CarBridgeAccess.DEFAULT_LOCAL_DOMAIN }}:${CarBridgeService.PORT} in Tesla browser. Phone must remain hotspot owner. If pairing/connect ports change, reopen Wireless debugging and use Discover/Pair again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
