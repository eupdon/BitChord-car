package com.music.bitchord.car

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the process alive while the user is in Settings > Wireless debugging,
 * watches mDNS for the pairing service, and shows the code-input notification
 * as soon as the pairing dialog is open. Same idea as Shizuku's AdbPairingService.
 */
class CarBridgePairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CarBridgePairingNotifier.ensureChannel(this)
        val first = CarBridgePairingNotifier.buildInput(this, null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(CarBridgePairingNotifier.NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(CarBridgePairingNotifier.NOTIF_ID, first)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        CarBridgeAdb.startDiscovery(this)
        job?.cancel()
        job = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        var lastKey: String? = null
        withTimeoutOrNull(10 * 60_000L) {
            CarBridgeAdb.state
                .takeWhile { it.status != CarBridgeAdb.Status.READY }
                .collect { s ->
                    val port = s.discoveredPairPorts.firstOrNull()
                    val busy = s.status == CarBridgeAdb.Status.PAIRING ||
                        s.status == CarBridgeAdb.Status.CONNECTING
                    val error = if (s.status == CarBridgeAdb.Status.ERROR) s.detail else null

                    // Only re-post when something meaningful changed. Re-posting while the
                    // user is typing in the notification wipes what they typed.
                    Log.d(TAG, "state=${s.status} port=$port busy=$busy detail=${s.detail}")
                    val key = "$port|$busy|$error"
                    if (key == lastKey) return@collect
                    lastKey = key

                    val n = when {
                        busy -> CarBridgePairingNotifier.buildStatus(this@CarBridgePairingService, s.detail, busy = true)
                        else -> CarBridgePairingNotifier.buildInput(this@CarBridgePairingService, port, error)
                    }
                    CarBridgePairingNotifier.notify(this@CarBridgePairingService, n)
                }
        }

        if (CarBridgeAdb.state.value.status == CarBridgeAdb.Status.READY) {
            CarBridgePairingNotifier.notify(
                this,
                CarBridgePairingNotifier.buildStatus(this, "Paired and connected. You can return to BitChord.", busy = false)
            )
            delay(2000)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Keep discovery alive only when the bridge itself wants it.
        if (!CarBridgeController.isEnabled(this)) CarBridgeAdb.stopDiscovery()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CarBridgePairingSvc"
    }
}
