package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material3.AlertDialog
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

    var showPairDialog by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CarBridgeDnsService.start(context)
            CarBridgeController.refreshRunningState(context)
        }
    }

    LaunchedEffect(Unit) {
        CarBridgeController.load(context)
    }

    LaunchedEffect(enabled, mode) {
        if (enabled && mode == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            CarBridgeAdb.startDiscovery(context)
            CarBridgeController.refreshRunningState(context)
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text("Pair Wireless Debugging") },
            text = {
                Column {
                    Text(
                        "1. Open Wireless debugging\n" +
                            "2. Tap 'Pair device with pairing code'\n" +
                            "3. Keep that screen visible (use split screen/pop-up view) and enter the 6-digit code here.\n\n" +
                            "Leaving the pairing screen may cancel pairing on some Android versions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-digit pairing code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val pairPort = adb.pairPort ?: adb.discoveredPairPorts.firstOrNull()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (pairPort != null) "Detected pairing port: $pairPort" else "Pairing port will be auto-detected. If not found, open pairing screen and wait.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pairPort = adb.pairPort ?: adb.discoveredPairPorts.firstOrNull()
                    CarBridgeAdb.pair(context, pairingCode, pairPort)
                    if (pairingCode.trim().length >= 6 && pairPort != null) {
                        showPairDialog = false
                        pairingCode = ""
                    }
                }) { Text("Pair") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val opened = runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }.isSuccess
                        if (!opened) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Open settings") }
                    TextButton(onClick = { showPairDialog = false }) { Text("Cancel") }
                }
            },
        )
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
                CarBridgeAccessMode.TESLA_LOCAL_DOMAIN -> "Built-in Local Domain mode. Ports/IP auto-handled; pairing may be required once."
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
                    "Tesla blocks private IPs. BitChord handles Local Domain automatically: discovers Wireless Debugging, connects ADB, applies route, and serves DNS for the configured domain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("URL: ${CarBridgeController.accessUrl(context)}", style = MaterialTheme.typography.bodySmall)
                Text("ADB: ${adb.status} - ${adb.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Route: ${if (route.applied) "active" else "inactive"} - ${route.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("DNS VPN: ${if (CarBridgeDnsService.running) "running" else "stopped"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { CarBridgeAdb.startDiscovery(context) }) { Text("Auto connect") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        CarBridgeAdb.startDiscovery(context)
                        showPairDialog = true
                    }) { Text("Pair") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val opened = runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }.isSuccess
                        if (!opened) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    }) { Text("Wireless debugging") }
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
                    "If pairing is required: tap Pair, open Wireless debugging, use split screen/pop-up view, tap 'Pair device with pairing code', then enter the code in BitChord.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
