package com.music.bitchord.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.NetworkInterface
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.util.Locale
import java.util.UUID

/**
 * Hosts the car web UI and its WebSocket bridge on the phone.
 *
 * The phone is the hotspot, so the car only needs to open the URL shown in the
 * persistent notification. The token is baked into the served HTML; the browser
 * never has to type it, but a device that can reach the LAN still needs the token
 * to open a socket. Only one car session is served at a time.
 */
class CarBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var server: EmbeddedServer<*, *>? = null
    private var session: DefaultWebSocketServerSession? = null
    private var bridge: CarSessionController? = null
    private var pusher: Job? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val token: String by lazy {
        var t = prefs.getString(KEY_TOKEN, null)
        if (t.isNullOrBlank()) {
            t = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_TOKEN, t).apply()
        }
        t
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        bridge = CarSessionController(this) { state ->
            scope.launch { sendJson(json.encodeToString(StateDto.serializer(), state)) }
        }

        // The browser extrapolates the playhead; this keeps it from drifting.
        pusher = scope.launch {
            while (isActive) {
                if (session != null) bridge?.publishState()
                delay(PUSH_INTERVAL_MS)
            }
        }

        val html = readAsset("index.html").replace("__TOKEN__", token)
        val js = readAsset("app.js")
        val css = readAsset("style.css")

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            // Default install; the 2s state push below is the keepalive, so no
            // version-specific pingPeriod/pingInterval API is needed here.
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/app.js") {
                    call.respondText(js, ContentType.Application.JavaScript)
                }
                get("/style.css") {
                    call.respondText(css, ContentType.Text.Css)
                }
                webSocket("/ws") {
                    if (call.request.queryParameters["token"] != token) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
                        return@webSocket
                    }

                    session?.let { old ->
                        runCatching {
                            old.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "replaced"))
                        }
                    }
                    session = this

                    val initial = bridge?.currentState() ?: StateDto()
                    sendJson(json.encodeToString(StateDto.serializer(), initial))

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val command = runCatching {
                                json.decodeFromString(CommandDto.serializer(), frame.readText())
                            }.getOrNull() ?: continue

                            bridge?.handle(command) { result ->
                                scope.launch {
                                    sendJson(json.encodeToString(ResultDto.serializer(), result))
                                }
                            }
                        }
                    } finally {
                        if (session === this) session = null
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pusher?.cancel()
        bridge?.release()
        session = null
        server?.stop(1_000L, 2_000L)
        server = null
        scope.cancel()
        super.onDestroy()
    }

    private fun readAsset(name: String): String =
        assets.open("car/$name").bufferedReader().use { it.readText() }

    private suspend fun sendJson(text: String) {
        val current = session ?: return
        sendMutex.withLock {
            runCatching { current.outgoing.send(Frame.Text(text)) }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "car_bridge"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BitChord Car Bridge",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CarBridgeService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BitChord Car Bridge")
            .setContentText("http://${phoneIp()}:$PORT/")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    /** Hotspot address first; fall back to any non-loopback IPv4 interface. */
    private fun phoneIp(): String {
        @Suppress("DEPRECATION")
        val address = (getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.connectionInfo?.ipAddress ?: 0
        if (address != 0) {
            return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                address and 0xff,
                address shr 8 and 0xff,
                address shr 16 and 0xff,
                address shr 24 and 0xff,
            )
        }
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
            ?: "192.168.43.1"
    }

    companion object {
        const val PORT = 8777
        private const val PREFS = "car_bridge"
        private const val KEY_TOKEN = "token"
        private const val NOTIFICATION_ID = 8777
        private const val ACTION_STOP = "com.music.bitchord.car.STOP"
        private const val PUSH_INTERVAL_MS = 2_000L
    }
}
