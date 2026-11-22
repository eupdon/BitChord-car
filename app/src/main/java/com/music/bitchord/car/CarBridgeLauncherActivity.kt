package com.music.bitchord.car

import android.app.Activity
import android.os.Bundle

/**
 * Small launcher entry point for the car bridge.
 *
 * The bridge runs in a foreground service, so a fresh install (or a force stop)
 * otherwise leaves no notification and no server until the next reboot. Tapping
 * this icon starts the service immediately; the activity itself is invisible.
 *
 * Kept outside MainActivity so upstream files stay untouched. It lives in the
 * shared app/src/main/java tree, so the TV module compiles it too even though
 * only the phone manifest declares the launcher.
 */
class CarBridgeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarBridgeController.setEnabled(this, true)
        finish()
    }
}
