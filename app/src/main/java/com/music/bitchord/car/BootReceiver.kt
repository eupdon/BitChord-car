package com.music.bitchord.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the bridge after boot or an update, but only when it is switched on.
 *
 * USER_PRESENT is included because some skins drop BOOT_COMPLETED for apps that
 * are not whitelisted for autostart; the unlock broadcast is delivered later,
 * when the hotspot and SharedPreferences are both usable.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            ACTION_QUICKBOOT_POWERON -> CarBridgeController.syncFromPrefs(context)
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
