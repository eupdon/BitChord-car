package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.car.CarBridgeController
import com.music.bitchord.ui.components.IosSwitch

/**
 * Settings entry for the Tesla/car web remote.
 *
 * Lives in its own file so SettingsSheet only needs one inserted call and the
 * bridge stays isolated from upstream code. The switch is the user's intent;
 * [CarBridgeController] starts or stops the foreground service to match it.
 */
@Composable
internal fun CarBridgeSettingsRow() {
    val context = LocalContext.current
    val enabled by CarBridgeController.enabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarBridgeController.load(context) }

    SettingsRow(
        icon = Icons.Rounded.DirectionsCar,
        title = "Car Bridge",
        subtitle = if (enabled) {
            "Web remote is on · port 8777"
        } else {
            "Web remote is off"
        },
        trailing = {
            IosSwitch(
                checked = enabled,
                onCheckedChange = { CarBridgeController.setEnabled(context, it) },
            )
        },
        onClick = { CarBridgeController.setEnabled(context, !enabled) },
    )
}
