package com.music.bitchord.ui.screens

import android.app.Activity
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
import com.music.bitchord.car.CarBridgeController
import com.music.bitchord.car.CarBridgeDnsService

/**
 * Settings entry for Tesla/car web remote.
 *
 * Local Domain mode assigns a public-looking IP to the phone itself through a
 * local VPN, so the Tesla browser can reach the on-phone web server via a
 * domain. No wireless debugging, ADB pairing, root or iptables is involved.
 */
@Composable
internal fun CarBridgeSettingsRow() {
    val context = LocalContext.current
    val enabled by CarBridgeController.enabled.collectAsStateWithLifecycle()
    val mode by CarBridgeController.accessMode.collectAsStateWithLifecycle()
    val dnsRunning by CarBridgeDnsService.runningFlow.collectAsStateWithLifecycle()

    var domain by remember { mutableStateOf(CarBridgeAccess.localDomain(context)) }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        CarBridgeController.onVpnPermissionResult(context, result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        CarBridgeController.load(context)
        domain = CarBridgeAccess.localDomain(context)
    }

    LaunchedEffect(vpnLauncher) {
        CarBridgeController.vpnPermissionRequests.collect {
            val prepare = CarBridgeController.vpnPrepareIntent(context)
            if (prepare != null) {
                vpnLauncher.launch(prepare)
            } else {
                CarBridgeController.onVpnPermissionResult(context, true)
            }
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
                CarBridgeAccessMode.TESLA_LOCAL_DOMAIN -> "Local domain via a local IP on this phone. No pairing or root required."
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
                    "Tesla blocks private IPs. Point this domain's A record to ${CarBridgeAccess.publicIp(context)} " +
                        "with Cloudflare proxy OFF (DNS only), then authorize the local VPN.",
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
                Text(
                    "Local IP: ${CarBridgeAccess.publicIp(context)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "VPN: ${if (dnsRunning) "active" else "not running"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { CarBridgeController.requestVpnPermission(context) }) {
                        Text("Authorize VPN")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        CarBridgeDnsService.stopDns(context)
                        CarBridgeController.refreshRunningState(context)
                    }) { Text("Stop VPN") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { CarBridgeController.refreshRunningState(context) }) { Text("Refresh") }
                }
                Text(
                    "On the phone: enable a 5 GHz hotspot. In the car: connect to that hotspot and open the URL above. " +
                        "If the page does not load, check that the A record is DNS only and points to the Local IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
