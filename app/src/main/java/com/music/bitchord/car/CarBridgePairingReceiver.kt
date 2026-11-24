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
                val port = intent.getIntExtra(CarBridgePairingNotifier.EXTRA_PAIR_PORT, -1).takeIf { it > 0 }
                // The pairing service (foreground) keeps the process alive while pairing runs.
                CarBridgePairingNotifier.handleCode(context, code, port)
            }
            CarBridgePairingNotifier.ACTION_CANCEL -> CarBridgePairingNotifier.cancel(context)
        }
    }
}
