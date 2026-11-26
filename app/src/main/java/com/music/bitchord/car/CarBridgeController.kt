package com.music.bitchord.car

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the car bridge settings.
 *
 * The preference is the user's intent; the service is started/stopped to match
 * it. Boot, unlock and app launch all go through [syncFromPrefs].
 */
object CarBridgeController {

    private const val PREFS = "car_bridge"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _accessMode = MutableStateFlow(CarBridgeAccessMode.DIRECT_IP)
    val accessMode: StateFlow<CarBridgeAccessMode> = _accessMode.asStateFlow()

    /**
     * VPN authorization must be requested from an Activity. Boot/app-start
     * paths emit here; the settings screen collects it and launches the
     * system consent dialog.
     */
    private val _vpnPermissionRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1
    )
    val vpnPermissionRequests: SharedFlow<Unit> = _vpnPermissionRequests.asSharedFlow()

    /** Call from the UI so state reflects persisted choices. */
    fun load(context: Context) {
        val app = context.applicationContext
        _enabled.value = isEnabled(app)
        _accessMode.value = CarBridgeAccess.mode(app)
        syncFromPrefs(app)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun vpnPrepareIntent(context: Context): Intent? =
        CarBridgeDnsService.prepareIntent(context.applicationContext)

    /** Starts the local-IP service when already authorized, otherwise asks the UI. */
    fun requestVpnPermission(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || CarBridgeAccess.mode(app) != CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) return
        if (vpnPrepareIntent(app) == null) {
            CarBridgeDnsService.start(app)
            refreshRunningState(app)
        } else {
            _vpnPermissionRequests.tryEmit(Unit)
        }
    }

    /** Called by the ActivityResultLauncher in the settings screen. */
    fun onVpnPermissionResult(context: Context, granted: Boolean) {
        val app = context.applicationContext
        if (granted && isEnabled(app) && CarBridgeAccess.mode(app) == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            CarBridgeDnsService.start(app)
        } else {
            CarBridgeDnsService.stopDns(app)
        }
        refreshRunningState(app)
    }

    fun setAccessMode(context: Context, mode: CarBridgeAccessMode) {
        val app = context.applicationContext
        CarBridgeAccess.setMode(app, mode)
        _accessMode.value = mode
        if (isEnabled(app)) {
            if (mode == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
                requestVpnPermission(app)
            } else {
                CarBridgeDnsService.stopDns(app)
            }
            refreshRunningState(app)
        } else {
            CarBridgeDnsService.stopDns(app)
        }
    }

    fun accessUrl(context: Context): String = CarBridgeAccess.accessUrl(context.applicationContext)

    /**
     * Make the running service match the saved switch. Safe to call repeatedly.
     */
    fun syncFromPrefs(context: Context) {
        val app = context.applicationContext
        val on = isEnabled(app)
        _enabled.value = on
        _accessMode.value = CarBridgeAccess.mode(app)
        val intent = Intent(app, CarBridgeService::class.java)
        if (on) {
            if (!CarBridgeService.running) {
                runCatching { ContextCompat.startForegroundService(app, intent) }
            }
            if (_accessMode.value == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
                requestVpnPermission(app)
            } else {
                CarBridgeDnsService.stopDns(app)
            }
            refreshRunningState(app)
        } else {
            CarBridgeDnsService.stopDns(app)
            if (CarBridgeService.running) app.stopService(intent)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        val intent = Intent(app, CarBridgeService::class.java)
        if (value) {
            runCatching { ContextCompat.startForegroundService(app, intent) }
            if (CarBridgeAccess.mode(app) == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
                requestVpnPermission(app)
            }
            refreshRunningState(app)
        } else {
            CarBridgeDnsService.stopDns(app)
            app.stopService(intent)
        }
    }

    /**
     * Force a fresh start without changing the saved on/off state.
     *
     * Used by the automation control entry points: after the phone hotspot has
     * been down and up, the VPN tun and the local socket can be left pointing at
     * a dead link. Tearing both down and bringing them back is what makes the
     * car browser reach 169.254.88.1 again.
     */
    fun restart(context: Context) {
        val app = context.applicationContext
        val on = isEnabled(app)
        CarBridgeDnsService.stopDns(app)
        val intent = Intent(app, CarBridgeService::class.java)
        if (CarBridgeService.running) app.stopService(intent)
        _enabled.value = on
        _accessMode.value = CarBridgeAccess.mode(app)
        if (on) syncFromPrefs(app)
    }

    /** The notification's Stop action: remember the off state without re-calling stopService. */
    fun markStopped(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = false
        CarBridgeDnsService.stopDns(app)
    }

    fun refreshRunningState(context: Context) {
        val app = context.applicationContext
        if (CarBridgeService.running) {
            runCatching {
                app.startService(
                    Intent(app, CarBridgeService::class.java)
                        .setAction(CarBridgeService.ACTION_REFRESH)
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
