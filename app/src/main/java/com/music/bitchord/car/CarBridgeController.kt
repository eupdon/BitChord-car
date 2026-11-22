package com.music.bitchord.car

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the Settings toggle.
 *
 * The preference is the user's intent; the service is started/stopped to match
 * it. Boot, unlock and app launch all go through [syncFromPrefs], so a stale
 * process or a receiver the OEM skipped cannot leave the bridge off while the
 * switch says on — or running while the switch says off.
 */
object CarBridgeController {

    private const val PREFS = "car_bridge"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** Call from the UI so the switch reflects the persisted choice. */
    fun load(context: Context) {
        syncFromPrefs(context)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Make the running service match the saved switch. Safe to call repeatedly:
     * starting an already-running service only refreshes its notification, and
     * stopping one that is already gone is a no-op.
     */
    fun syncFromPrefs(context: Context) {
        val on = isEnabled(context)
        _enabled.value = on
        val intent = Intent(context, CarBridgeService::class.java)
        if (on) {
            if (!CarBridgeService.running) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            }
        } else if (CarBridgeService.running) {
            context.stopService(intent)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value

        // The explicit toggle always acts, even if a stale `running` flag says
        // otherwise — this is the path that has always worked from Settings.
        val intent = Intent(context, CarBridgeService::class.java)
        if (value) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }

    /** The notification's Stop action: remember the off state without re-calling stopService. */
    fun markStopped(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
