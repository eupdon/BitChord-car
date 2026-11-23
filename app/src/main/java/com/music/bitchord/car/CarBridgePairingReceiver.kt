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
                val port = intent.getIntExtra("pair_port", -1).takeIf { it > 0 }
                if (port != null) {
                    CarBridgeAdb.setManualEndpoint(context, "127.0.0.1", CarBridgeAdb.state.value.connectPort, port)
                }
                CarBridgePairingNotifier.handleCode(context, code)
            }
            CarBridgePairingNotifier.ACTION_CANCEL -> {
                CarBridgePairingNotifier.cancel(context)
            }
        }
    }
}