package com.music.bitchord.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.music.bitchord.R

/**
 * Wireless-debugging pairing notification (Shizuku style).
 *
 * The notification itself is owned by [CarBridgePairingService], a foreground
 * service. That is what keeps the process alive and mDNS callbacks flowing
 * while the user is inside Settings. This object only builds the notifications
 * and forwards the typed code.
 */
object CarBridgePairingNotifier {

    // New id: a channel's sound/importance can't be changed after creation.
    const val CHANNEL_ID = "car_bridge_pairing_v2"
    const val NOTIF_ID = 8778
    const val ACTION_PAIR_CODE = "com.music.bitchord.car.action.PAIR_CODE"
    const val ACTION_CANCEL = "com.music.bitchord.car.action.PAIR_CANCEL"
    const val KEY_PAIR_CODE = "pair_code"
    const val EXTRA_PAIR_PORT = "pair_port"

    private const val TAG = "CarBridgePairing"

    /** Called from the Pair button (app is in the foreground, so the FGS start is allowed). */
    fun show(context: Context) {
        val app = context.applicationContext
        if (!canPost(app)) {
            Toast.makeText(app, "Notification permission required for pairing code input.", Toast.LENGTH_LONG).show()
            return
        }
        ensureChannel(app)
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, CarBridgePairingService::class.java))
        }.onFailure {
            Log.e(TAG, "Could not start pairing service", it)
            Toast.makeText(app, "Could not start pairing: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** From the notification's reply action. */
    fun handleCode(context: Context, code: String, port: Int?) {
        val app = context.applicationContext
        // "123456" or "123456 37123" (code + port shown in the pairing dialog).
        val tokens = code.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val digits = tokens.firstOrNull().orEmpty()
        val manualPort = tokens.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1024..65535 }
        Log.d(TAG, "handleCode: codeLen=${digits.length} port=$port manualPort=$manualPort")
        if (digits.length != 6) {
            // Must re-post anyway, otherwise the reply spinner never goes away.
            notify(app, buildInput(app, port, "Invalid code. Enter the 6 digits (optionally followed by the port)."))
            return
        }
        CarBridgeAdb.pair(app, digits, manualPort ?: port)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, CarBridgePairingService::class.java))
        NotificationManagerCompat.from(app).cancel(NOTIF_ID)
    }

    // ---- notification builders (used by the service) ----

    /** The notification with the text field. It is shown even before the pairing port is found. */
    internal fun buildInput(ctx: Context, port: Int?, hint: String? = null): Notification {
        val replyIntent = Intent(ctx, CarBridgePairingReceiver::class.java)
            .setAction(ACTION_PAIR_CODE)
            .putExtra(EXTRA_PAIR_PORT, port ?: -1)
        val replyPending = PendingIntent.getBroadcast(
            ctx, 100, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        val remoteInput = RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel("6-digit pairing code")
            .build()
        val replyAction = NotificationCompat.Action.Builder(0, "Enter pairing code", replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
        val text = hint ?: if (port != null) {
            "Pairing dialog detected. Tap Enter pairing code and type the 6 digits."
        } else {
            "Open Wireless debugging > Pair device with pairing code, then tap Enter pairing code and type the 6 digits."
        }
        return base(ctx, alert = true) // alert = heads-up pop-up
            .setContentTitle("Wireless debugging pairing")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(replyAction)
            .addAction(0, "Cancel", cancelPending(ctx))
            .build()
    }

    /** Busy / done / failed, no input field. */
    internal fun buildStatus(ctx: Context, text: String, busy: Boolean): Notification =
        base(ctx, alert = false)
            .setContentTitle("Wireless debugging pairing")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(busy)
            .setProgress(0, 0, busy)
            .build()

    @SuppressLint("MissingPermission")
    internal fun notify(ctx: Context, n: Notification) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
        } catch (e: Throwable) {
            // Was runCatching before, which hid why nothing appeared.
            Log.e(TAG, "notify failed", e)
        }
    }

    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Car Bridge pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wireless debugging pairing code input"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun base(ctx: Context, alert: Boolean): NotificationCompat.Builder {
        val openIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            ctx, 102, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            // Monochrome vector. The old code fell back to the adaptive launcher
            // mipmap, which is not a valid status-bar icon.
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun cancelPending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 101,
            Intent(ctx, CarBridgePairingReceiver::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    internal fun canPost(context: Context): Boolean {
        val permOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
