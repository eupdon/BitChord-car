package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import com.music.bitchord.car.CarBridgeRouteManager
import com.music.bitchord.car.CarBridgeService
import com.music.bitchord.car.CarBridgeShizuku

/**
 * Settings entry for the Tesla/car web remote.
 *
 * Lives in its own file so SettingsSheet only needs one inserted call and the
 * bridge stays isolated from upstream code.
 */
@Composable
internal fun CarBridgeSettingsRow() {
    val context = LocalContext.current
    val enabled by CarBridgeController.enabled.collectAsStateWithLifecycle()
    val mode by CarBridgeController.accessMode.collectAsStateWithLifecycle()
    val shizuku by CarBridgeShizuku.state.collectAsStateWithLifecycle()
    val route by CarBridgeRouteManager.state.collectAsStateWithLifecycle()

    var domain by remember { mutableStateOf(CarBridgeAccess.localDomain(context)) }
    var publicIp by remember { mutableStateOf(CarBridgeAccess.publicIp(context)) }

    LaunchedEffect(Unit) {
        CarBridgeController.load(context)
        domain = CarBridgeAccess.localDomain(context)
        publicIp = CarBridgeAccess.publicIp(context)
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
                CarBridgeAccessMode.TESLA_LOCAL_DOMAIN -> "Local domain workaround (public-looking host mapped by Shizuku)"
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
                    "Tesla blocks private IPs in browser. This mode uses a public-looking domain/IP and maps it locally through Shizuku/ADB privileges. Initial wireless debugging setup is usually required on non-rooted phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = {
                        domain = it
                        CarBridgeAccess.setLocalDomain(context, it)
                    },
                    label = { Text("Local domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = publicIp,
                    onValueChange = {
                        publicIp = it
                        CarBridgeAccess.setPublicIp(context, it)
                    },
                    label = { Text("Public-looking IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Shizuku: ${shizuku.status} - ${shizuku.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Route: ${if (route.applied) "active" else "inactive"} - ${route.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }
                    }) { Text("Developer options") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val wireless = runCatching {
                            context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                        }.isSuccess
                        if (!wireless) {
                            runCatching {
                                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }
                        }
                    }) { Text("Wireless debugging") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        val pkg = "moe.shizuku.privileged.api"
                        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            context.startActivity(launch)
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                            }
                        }
                    }) { Text("Open Shizuku") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { CarBridgeShizuku.requestPermission() }) { Text("Request permission") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { CarBridgeController.refreshRunningState(context) }) { Text("Apply/Test") }
                }
                Text(
                    "After setup, open http://${domain.ifBlank { CarBridgeAccess.DEFAULT_LOCAL_DOMAIN }}:${CarBridgeService.PORT} in Tesla browser. Phone must remain hotspot owner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
