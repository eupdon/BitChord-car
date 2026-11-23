package com.music.bitchord.car

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object CarBridgePairingNotifier {

    const val CHANNEL_ID = "car_bridge_pairing"
    const val NOTIF_ID = 8778
    const val ACTION_PAIR_CODE = "com.music.bitchord.car.action.PAIR_CODE"
    const val ACTION_CANCEL = "com.music.bitchord.car.action.PAIR_CANCEL"
    const val KEY_PAIR_CODE = "pair_code"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    fun show(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        if (!canPost(app)) {
            Toast.makeText(app, "Notification permission required for pairing code input.", Toast.LENGTH_LONG).show()
            return
        }
        CarBridgeAdb.startDiscovery(app)
        post(
            app,
            "Wireless debugging pairing",
            "Open Wireless debugging > Pair device with pairing code. Pull this notification down and tap Enter pairing code."
        )
        startWatching(app)
    }

    fun handleCode(context: Context, code: String) {
        val app = context.applicationContext
        val trimmed = code.trim().filter(Char::isDigit).take(6)
        if (trimmed.length < 6) {
            post(app, "Wireless debugging pairing", "Invalid code. Enter 6 digits.")
            return
        }
        post(app, "Wireless debugging pairing", "Pairing with code $trimmed ...")
        CarBridgeAdb.pair(app, trimmed, null)
        startWatching(app)
    }

    fun cancel(context: Context) {
        watchJob?.cancel()
        watchJob = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIF_ID)
    }

    private fun startWatching(context: Context) {
        val app = context.applicationContext
        watchJob?.cancel()
        watchJob = scope.launch {
            var lastDetail: String? = null
            repeat(120) { // ~2 min
                val s = CarBridgeAdb.state.value
                val detail = s.detail
                val pairPort = s.pairPort ?: s.discoveredPairPorts.firstOrNull()
                val text = when {
                    s.status == CarBridgeAdb.Status.READY || detail.contains("Pairing succeeded", true) ->
                        "Paired/connected. You can return to BitChord."
                    s.status == CarBridgeAdb.Status.PAIRING -> detail
                    s.status == CarBridgeAdb.Status.ERROR -> detail
                    pairPort != null -> "Pairing port detected: $pairPort. Enter 6-digit code."
                    else -> "Open Wireless debugging > Pair device with pairing code, then enter code here."
                }
                if (text != lastDetail) {
                    post(app, "Wireless debugging pairing", text)
                    lastDetail = text
                }
                if (s.status == CarBridgeAdb.Status.READY || detail.contains("Pairing succeeded", true)) {
                    delay(2000)
                    cancel(app)
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun post(context: Context, title: String, text: String) {
        val app = context.applicationContext
        ensureChannel(app)
        if (!canPost(app)) return

        val replyIntent = Intent(app, CarBridgePairingReceiver::class.java).setAction(ACTION_PAIR_CODE)
        val replyPending = PendingIntent.getBroadcast(
            app, 100, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutability()
        )
        val remoteInput = RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel("6-digit pairing code")
            .build()
        val replyAction = NotificationCompat.Action.Builder(0, "Enter pairing code", replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val cancelIntent = Intent(app, CarBridgePairingReceiver::class.java).setAction(ACTION_CANCEL)
        val cancelPending = PendingIntent.getBroadcast(
            app, 101, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutability()
        )

        val openIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            app, 102, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutability()
        )

        val notif = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // fallback; replaced if app icon available
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .addAction(replyAction)
            .addAction(0, "Cancel", cancelPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        // Use app icon if present
        val iconId = app.resources.getIdentifier("ic_launcher_foreground", "drawable", app.packageName)
            .takeIf { it != 0 }
            ?: app.applicationInfo.icon
        val fixed = NotificationCompat.Builder(app, notif).setSmallIcon(iconId).build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIF_ID, fixed) }
    }

    private fun canPost(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Car Bridge pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wireless debugging pairing code input"
            }
        )
    }

    private fun pendingIntentMutability(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
}