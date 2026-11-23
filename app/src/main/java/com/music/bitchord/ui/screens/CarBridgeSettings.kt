package com.music.bitchord.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.car.CarBridgeAccess
import com.music.bitchord.car.CarBridgeAccessMode
import com.music.bitchord.car.CarBridgeAdb
import com.music.bitchord.car.CarBridgeController
import com.music.bitchord.car.CarBridgeDnsService
import com.music.bitchord.car.CarBridgePairingNotifier
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
    val dnsRunning by CarBridgeDnsService.runningFlow.collectAsStateWithLifecycle()

    var domain by remember { mutableStateOf(CarBridgeAccess.localDomain(context)) }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CarBridgeDnsService.start(context)
            CarBridgeController.refreshRunningState(context)
        }
    }

    fun openWirelessDebugging() {
        val opened = runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }.isSuccess
        if (!opened) runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    fun startPairingNotificationFlow() {
        CarBridgeAdb.startDiscovery(context)
        val needsNotifPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifPerm) {
            // Avoid in-app permission launcher here: on some ROMs/flows it can recreate the host activity.
            // Send user to app notification settings, then they can tap Pair again.
            Toast.makeText(context, "Allow BitChord notifications, then tap Pair again.", Toast.LENGTH_LONG).show()
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
            runCatching { context.startActivity(intent) }
            return
        }
        CarBridgePairingNotifier.show(context)
        openWirelessDebugging()
        Toast.makeText(context, "Pairing notification posted. Pull down notification and tap 'Enter pairing code'.", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        CarBridgeController.load(context)
        domain = CarBridgeAccess.localDomain(context)
    }

    LaunchedEffect(enabled, mode) {
        if (enabled && mode == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            CarBridgeAdb.startDiscovery(context)
            CarBridgeController.refreshRunningState(context)
        }
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
                    "Tesla blocks private IPs. BitChord handles Local Domain automatically. Pairing is only needed once; pairing overlay can stay over Wireless Debugging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = {
                        domain = it
                        CarBridgeAccess.setLocalDomain(context, it)
                        CarBridgeController.refreshRunningState(context)
                    },
                    label = { Text("Local domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("URL: ${CarBridgeController.accessUrl(context)}", style = MaterialTheme.typography.bodySmall)
                Text("ADB: ${adb.status} - ${adb.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Route: ${if (route.applied) "active" else "inactive"} - ${route.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("DNS VPN: ${if (dnsRunning) "running" else "stopped"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { CarBridgeAdb.startDiscovery(context); CarBridgeAdb.connect(context, null) }) { Text("Auto connect") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { startPairingNotificationFlow() }) { Text("Pair") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { openWirelessDebugging() }) { Text("Wireless debugging") }
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
                    "Pair: tap Pair → Wireless debugging opens → tap 'Pair device with pairing code' → pull down BitChord pairing notification → tap 'Enter pairing code' and input 6 digits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
