package com.music.bitchord.car

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Entry points used by Galaxy Routines / Tasker / MacroDroid to control the
 * car bridge without opening the settings screen.
 *
 * ON always forces a restart: after the phone hotspot has gone down and come
 * back up, the VPN tun can keep a dead link, which is why simply "turning it
 * on" is not enough — the service and tunnel have to be recreated.
 */
object CarBridgeControl {
    const val ACTION_ENABLE = "com.music.bitchord.car.ENABLE"
    const val ACTION_DISABLE = "com.music.bitchord.car.DISABLE"
    const val ACTION_TOGGLE = "com.music.bitchord.car.TOGGLE"

    fun apply(context: Context, action: String?) {
        when (action) {
            ACTION_ENABLE -> CarBridgeController.restart(context, true)
            ACTION_DISABLE -> CarBridgeController.setEnabled(context, false)
            ACTION_TOGGLE ->
                CarBridgeController.restart(context, !CarBridgeController.isEnabled(context))
        }
    }
}

/** No-UI target for app shortcuts and "run app" style automation. */
class CarBridgeControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action ?: CarBridgeControl.ACTION_TOGGLE
        val enables = when (action) {
            CarBridgeControl.ACTION_ENABLE -> true
            CarBridgeControl.ACTION_DISABLE -> false
            else -> !CarBridgeController.isEnabled(this)
        }
        CarBridgeControl.apply(this, action)

        // A fresh install may still need the system VPN consent dialog. If it
        // has been granted already, prepare() is null and nothing is shown.
        if (enables && CarBridgeAccess.mode(this) == CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            CarBridgeController.vpnPrepareIntent(this)?.let { prepare ->
                startActivityForResult(prepare, REQUEST_VPN)
                return
            }
        }
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            CarBridgeController.onVpnPermissionResult(this, resultCode == RESULT_OK)
        }
        finish()
    }

    private companion object {
        const val REQUEST_VPN = 9077
    }
}
