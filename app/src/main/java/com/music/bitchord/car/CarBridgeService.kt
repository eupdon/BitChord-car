package com.music.bitchord.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
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
    /** Artwork is fetched by the phone; the browser only ever asks this server. */
    private val artClient = OkHttpClient()

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
        val logo = readAsset("logo-mark.svg")
        val fonts = mapOf(
            "sf-pro-display-regular.woff2" to readBytesAsset("car/fonts/sf-pro-display-regular.woff2"),
            "sf-pro-display-bold.woff2" to readBytesAsset("car/fonts/sf-pro-display-bold.woff2"),
        )

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            // Default install; the 2s state push below is the keepalive, so no
            // version-specific pingPeriod/pingInterval API is needed here.
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(html, ContentType.Text.Html)
                }
                // Cheap connectivity check: open http://<phone-ip>:8777/health
                get("/health") {
                    call.respondText("ok", ContentType.Text.Plain)
                }
                get("/app.js") {
                    call.respondText(js, ContentType.Application.JavaScript)
                }
                get("/style.css") {
                    call.respondText(css, ContentType.Text.CSS)
                }
                get("/logo-mark.svg") {
                    call.respondText(logo, ContentType.parse("image/svg+xml"))
                }
                get("/fonts/{name}") {
                    val bytes = fonts[call.parameters["name"]]
                    if (bytes == null) call.respond(HttpStatusCode.NotFound)
                    else call.respondBytes(bytes, ContentType.parse("font/woff2"))
                }
                // The car never talks to an image host itself. This keeps all
                // network identity (and any cookie that might be needed) on the
                // phone, and covers Tesla browsers with no direct internet.
                get("/art") {
                    val raw = call.request.queryParameters["u"]
                    val url = runCatching { raw?.let { URL(it) } }.getOrNull()
                    if (url == null || url.protocol.lowercase() !in setOf("http", "https")) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    val response = withContext(Dispatchers.IO) {
                        runCatching {
                            artClient.newCall(Request.Builder().url(url).build()).execute()
                        }.getOrNull()
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.BadGateway)
                        return@get
                    }
                    response.use { res ->
                        if (!res.isSuccessful) {
                            call.respond(HttpStatusCode.BadGateway)
                        } else {
                            val bytes = res.body?.bytes() ?: ByteArray(0)
                            val type = res.header("Content-Type")
                                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                                ?: ContentType.Image.JPEG
                            call.respondBytes(bytes, type)
                        }
                    }
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
                            val text = frame.readText()

                            // New UI uses typed calls; the original client's
                            // flat commands stay supported during the switch.
                            val rpc = runCatching {
                                json.decodeFromString(CallDto.serializer(), text)
                            }.getOrNull()
                            if (rpc != null) {
                                bridge?.handle(rpc) { result ->
                                    scope.launch {
                                        sendJson(json.encodeToString(ResultDto.serializer(), result))
                                    }
                                }
                                continue
                            }

                            val command = runCatching {
                                json.decodeFromString(CommandDto.serializer(), text)
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
            CarBridgeController.markStopped(this)
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

    private fun readBytesAsset(name: String): ByteArray =
        assets.open(name).use { it.readBytes() }

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

    /**
     * Address a client on the phone's hotspot can actually reach.
     *
     * WifiManager.connectionInfo is unreliable in AP mode (often 0, or the
     * station-side address), so enumerate interfaces and prefer the well-known
     * soft-AP addresses before any other LAN address. Mobile-data addresses
     * (10.*) are deliberately last — they are not reachable from the hotspot.
     */
    private fun phoneIp(): String {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList()
            .orEmpty()
            .asSequence()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress }
            .toList()

        return addresses.firstOrNull { it == "192.168.137.1" || it == "192.168.43.1" }
            ?: addresses.firstOrNull { it.startsWith("192.168.") }
            ?: addresses.firstOrNull { !it.startsWith("10.") }
            ?: addresses.firstOrNull()
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
