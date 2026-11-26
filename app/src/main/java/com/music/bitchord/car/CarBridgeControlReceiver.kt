package com.music.bitchord.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Broadcast target for Tasker / MacroDroid / `am broadcast`. */
class CarBridgeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CarBridgeControl.apply(context.applicationContext, intent.action)
    }
}
