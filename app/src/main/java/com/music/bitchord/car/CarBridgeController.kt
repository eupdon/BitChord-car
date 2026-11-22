package com.music.bitchord.car

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the car bridge settings.
 *
 * The preference is the user's intent; the service is started/stopped to match
 * it. Boot, unlock and app launch all go through [syncFromPrefs].
 */
object CarBridgeController {

    private const val PREFS = "car_bridge"
    private const val KEY_ENABLED = "enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _accessMode = MutableStateFlow(CarBridgeAccessMode.DIRECT_IP)
    val accessMode: StateFlow<CarBridgeAccessMode> = _accessMode.asStateFlow()

    /** Call from the UI so state reflects persisted choices. */
    fun load(context: Context) {
        val app = context.applicationContext
        _enabled.value = isEnabled(app)
        _accessMode.value = CarBridgeAccess.mode(app)
        CarBridgeAdb.init(app)
        syncFromPrefs(app)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setAccessMode(context: Context, mode: CarBridgeAccessMode) {
        val app = context.applicationContext
        CarBridgeAccess.setMode(app, mode)
        _accessMode.value = mode
        if (isEnabled(app)) {
            if (mode == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
                // DNS VPN requires user approval; service start only when already prepared.
                if (CarBridgeDnsService.prepareIntent(app) == null) {
                    CarBridgeDnsService.start(app)
                }
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
            } else {
                refreshRunningState(app)
            }
        } else {
            scope.launch { CarBridgeRouteManager.clear(app) }
            CarBridgeDnsService.stopDns(app)
            CarBridgeAdb.close()
            if (CarBridgeService.running) app.stopService(intent)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        val intent = Intent(app, CarBridgeService::class.java)
        if (value) {
            ContextCompat.startForegroundService(app, intent)
            refreshRunningState(app)
        } else {
            scope.launch { CarBridgeRouteManager.clear(app) }
            CarBridgeDnsService.stopDns(app)
            CarBridgeAdb.close()
            app.stopService(intent)
        }
    }

    /** The notification's Stop action: remember the off state without re-calling stopService. */
    fun markStopped(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = false
        scope.launch { CarBridgeRouteManager.clear(app) }
        CarBridgeDnsService.stopDns(app)
        CarBridgeAdb.close()
    }

    fun refreshRunningState(context: Context) {
        val app = context.applicationContext
        CarBridgeAdb.refresh(app)
        scope.launch { CarBridgeRouteManager.apply(app) }
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
