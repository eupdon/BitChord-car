package com.music.bitchord.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Tesla Local Domain helper.
 *
 * Tesla's browser refuses RFC1918 destinations. This service uses Android's
 * local VPN API for one thing only: assigning the configured "public-looking"
 * IP (default 192.0.2.1) to this phone. The domain's A record must point to
 * that same IP, with Cloudflare proxy disabled (DNS only). The car resolves the
 * domain, sends the packet to its phone hotspot, and the phone delivers it to
 * its own tun address, where CarBridgeService (0.0.0.0:8777) is listening.
 *
 * Nothing is redirected, intercepted or collected: there is no upstream tunnel
 * and no DNS sniffing. The class name is kept for preference/manifest
 * compatibility with earlier builds.
 */
class CarBridgeDnsService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!CarBridgeController.isEnabled(this) ||
            CarBridgeAccess.mode(this) != CarBridgeAccessMode.TESLA_LOCAL_DOMAIN
        ) {
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!restartTunnel()) shutdown()
        return START_STICKY
    }

    override fun onDestroy() {
        closeInterface()
        running = false
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    private fun shutdown() {
        closeInterface()
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun closeInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    /** Recreates the tunnel so a changed IP/domain takes effect. */
    private fun restartTunnel(): Boolean {
        closeInterface()
        vpnInterface = establishInterface()
        running = vpnInterface != null
        return running
    }

    private fun establishInterface(): ParcelFileDescriptor? {
        val ip = CarBridgeAccess.publicIp(this)
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() as? Inet4Address
        if (address == null) {
            Log.w(TAG, "Invalid local IP: $ip")
            return null
        }
        val host = address.hostAddress ?: return null
        return runCatching {
            Builder()
                .setSession("BitChord Tesla Local IP")
                .setMtu(1500)
                // The address itself is the whole route: packets addressed to it
                // are delivered locally. Never add 0.0.0.0/0 — that would take
                // over the phone's own internet traffic for no reason.
                .addAddress(host, 32)
                .addRoute(host, 32)
                .also { builder ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        builder.setMetered(false)
                    }
                }
                .establish()
        }.onFailure {
            Log.w(TAG, "VPN establish failed", it)
        }.getOrNull()
    }

    private fun buildNotification(): Notification {
        val channelId = "car_bridge_dns"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Car Bridge Local IP", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable
        )
        val stop = PendingIntent.getService(
            this, 21, Intent(this, CarBridgeDnsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable
        )
        val domain = CarBridgeAccess.localDomain(this)
        val ip = CarBridgeAccess.publicIp(this)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BitChord Tesla Local IP")
            .setContentText("$domain -> $ip")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "CarBridgeDns"
        private const val NOTIFICATION_ID = 8778
        private const val ACTION_STOP = "com.music.bitchord.car.DNS_STOP"

        private val _runningFlow = MutableStateFlow(false)
        val runningFlow: StateFlow<Boolean> = _runningFlow.asStateFlow()

        var running: Boolean
            get() = _runningFlow.value
            private set(value) { _runningFlow.value = value }

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, CarBridgeDnsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun stopDns(context: Context) {
            val app = context.applicationContext
            running = false
            val stopIntent = Intent(app, CarBridgeDnsService::class.java).setAction(ACTION_STOP)
            val sent = runCatching { app.startService(stopIntent) }.isSuccess
            if (!sent) runCatching { app.stopService(Intent(app, CarBridgeDnsService::class.java)) }
        }

        fun prepareIntent(context: Context): Intent? = VpnService.prepare(context.applicationContext)
    }
}
