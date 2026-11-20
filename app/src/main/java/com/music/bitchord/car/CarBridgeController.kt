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
 * it. Boot and package-replace only restart the bridge when the toggle is on,
 * so an app that was never enabled stays quiet.
 */
object CarBridgeController {

    private const val PREFS = "car_bridge"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    /** Call once from the UI so the switch reflects the persisted choice. */
    fun load(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value

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
