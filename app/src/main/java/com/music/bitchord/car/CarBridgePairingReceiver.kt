package com.music.bitchord.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

class CarBridgePairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarBridgePairingNotifier.ACTION_PAIR_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(CarBridgePairingNotifier.KEY_PAIR_CODE)
                    ?.toString()
                    .orEmpty()
                CarBridgePairingNotifier.handleCode(context, code)
            }
            CarBridgePairingNotifier.ACTION_CANCEL -> {
                CarBridgePairingNotifier.cancel(context)
            }
        }
    }
}