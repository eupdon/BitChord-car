package com.music.bitchord.car

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File

/**
 * Embedded ADB manager for Tesla Local Domain mode.
 *
 * Goal: make BitChord self-sufficient. No external Shizuku app required.
 * The phone pairs/connects to its own Wireless Debugging ADB daemon via loopback,
 * then applies iptables redirect rules so Tesla browser can use a public-looking
 * host/IP that is mapped locally on the phone.
 */
object CarBridgeAdb {

    enum class Status {
        NOT_READY, DISCOVERING, PAIRING_REQUIRED, PAIRING, CONNECTING, READY, ERROR
    }

    data class State(
        val status: Status = Status.NOT_READY,
        val detail: String = "Wireless debugging not connected",
        val connectHost: String = "127.0.0.1",
        val connectPort: Int? = null,
        val pairPort: Int? = null,
        val discoveredConnectPorts: List<Int> = emptyList(),
        val discoveredPairPorts: List<Int> = emptyList(),
    )

    private const val TAG = "CarBridgeAdb"
    private const val PREFS = "car_bridge"
    private const val KEY_CONNECT_PORT = "adb_connect_port"
    private const val KEY_PAIR_PORT = "adb_pair_port"
    private const val KEY_CONNECT_HOST = "adb_connect_host"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var kadb: Kadb? = null
    @Volatile private var mdns: KadbMdnsAndroid? = null
    @Volatile private var discoveryJob: Job? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var certConfigured = false

    fun init(context: Context) {
        val app = context.applicationContext
        ensureCert(app)
        loadSavedEndpoint(app)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        init(app)
        scope.launch { ensureConnected(app, userInitiated = false) }
    }

    fun savedConnectHost(context: Context): String =
        prefs(context).getString(KEY_CONNECT_HOST, "127.0.0.1")?.trim().takeUnless { it.isNullOrBlank() } ?: "127.0.0.1"

    fun savedConnectPort(context: Context): Int? =
        prefs(context).getInt(KEY_CONNECT_PORT, -1).takeIf { it > 0 }

    fun savedPairPort(context: Context): Int? =
        prefs(context).getInt(KEY_PAIR_PORT, -1).takeIf { it > 0 }

    fun setManualEndpoint(context: Context, host: String, connectPort: Int?, pairPort: Int?) {
        val app = context.applicationContext
        prefs(app).edit()
            .putString(KEY_CONNECT_HOST, host.trim().ifBlank { "127.0.0.1" })
            .apply()
        if (connectPort != null && connectPort > 0) prefs(app).edit().putInt(KEY_CONNECT_PORT, connectPort).apply()
        if (pairPort != null && pairPort > 0) prefs(app).edit().putInt(KEY_PAIR_PORT, pairPort).apply()
        _state.value = _state.value.copy(
            connectHost = host.trim().ifBlank { "127.0.0.1" },
            connectPort = connectPort ?: _state.value.connectPort,
            pairPort = pairPort ?: _state.value.pairPort,
        )
    }

    fun startDiscovery(context: Context) {
        val app = context.applicationContext
        init(app)
        stopDiscovery()
        _state.value = _state.value.copy(status = Status.DISCOVERING, detail = "Discovering wireless debugging services...")
        acquireMulticastLock(app)
        try {
            val m = KadbMdnsAndroid(app)
            mdns = m
            m.start()
            discoveryJob = scope.launch {
                runCatching {
                    m.state.collect { st ->
                        val connectPorts = st.connectDevices.mapNotNull { it.port.takeIf { p -> p > 0 } }.distinct()
                        val pairPorts = st.pairDevices.mapNotNull { it.port.takeIf { p -> p > 0 } }.distinct()
                        val connectHost = st.connectDevices.firstOrNull()?.host?.takeIf { it.isNotBlank() } ?: _state.value.connectHost
                        val connectPort = connectPorts.firstOrNull() ?: _state.value.connectPort
                        val pairPort = pairPorts.firstOrNull() ?: _state.value.pairPort
                        if (connectPort != null && connectPort > 0) {
                            prefs(app).edit().putInt(KEY_CONNECT_PORT, connectPort).putString(KEY_CONNECT_HOST, connectHost).apply()
                        }
                        if (pairPort != null && pairPort > 0) {
                            prefs(app).edit().putInt(KEY_PAIR_PORT, pairPort).apply()
                        }
                        _state.value = _state.value.copy(
                            status = if (_state.value.status == Status.READY) Status.READY else Status.DISCOVERING,
                            detail = buildDiscoveryDetail(connectPorts, pairPorts),
                            connectHost = connectHost,
                            connectPort = connectPort,
                            pairPort = pairPort,
                            discoveredConnectPorts = connectPorts,
                            discoveredPairPorts = pairPorts,
                        )
                        if (_state.value.status != Status.READY && connectPort != null) {
                            ensureConnected(app, userInitiated = false)
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "mDNS collect failed", e)
                    _state.value = _state.value.copy(status = Status.ERROR, detail = e.message ?: "mDNS discovery failed")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mDNS start failed", e)
            _state.value = _state.value.copy(status = Status.ERROR, detail = e.message ?: "mDNS start failed")
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { mdns?.close() }
        mdns = null
        releaseMulticastLock()
    }

    fun pair(context: Context, pairingCode: String, pairPort: Int?) {
        val app = context.applicationContext
        init(app)
        val code = pairingCode.trim()
        val port = pairPort ?: _state.value.pairPort ?: savedPairPort(app)
        if (code.length < 6) {
            _state.value = _state.value.copy(status = Status.ERROR, detail = "Pairing code required")
            return
        }
        if (port == null || port <= 0) {
            _state.value = _state.value.copy(status = Status.ERROR, detail = "Pairing port required. Open Wireless debugging > Pair device with pairing code.")
            return
        }
        scope.launch {
            mutex.withLock {
                _state.value = _state.value.copy(status = Status.PAIRING, detail = "Pairing on 127.0.0.1:$port ...")
                val ok = runCatching {
                    Kadb.pair("127.0.0.1", port, code)
                }
                ok.onSuccess {
                    prefs(app).edit().putInt(KEY_PAIR_PORT, port).apply()
                    _state.value = _state.value.copy(status = Status.PAIRING_REQUIRED, detail = "Pairing succeeded. Connecting...")
                    ensureConnected(app, userInitiated = true)
                }.onFailure { e ->
                    Log.w(TAG, "Pair failed", e)
                    _state.value = _state.value.copy(status = Status.ERROR, detail = "Pairing failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun connect(context: Context, connectPort: Int?) {
        val app = context.applicationContext
        init(app)
        val port = connectPort ?: _state.value.connectPort ?: savedConnectPort(app)
        if (port != null && port > 0) {
            prefs(app).edit().putInt(KEY_CONNECT_PORT, port).apply()
            _state.value = _state.value.copy(connectPort = port)
        }
        scope.launch { ensureConnected(app, userInitiated = true) }
    }

    fun isReady(): Boolean = _state.value.status == Status.READY && kadb != null

    suspend fun ensureConnected(context: Context, userInitiated: Boolean): Boolean = mutex.withLock {
        val app = context.applicationContext
        init(app)
        val existing = kadb
        if (existing != null && runCatching { existing.connectionCheck() }.getOrDefault(false)) {
            _state.value = _state.value.copy(status = Status.READY, detail = "ADB connected")
            return@withLock true
        }
        closeKadb()

        val host = savedConnectHost(app)
        val port = _state.value.connectPort ?: savedConnectPort(app)
        if (port == null || port <= 0) {
            _state.value = _state.value.copy(
                status = Status.PAIRING_REQUIRED,
                detail = "No wireless debugging connect port. Enable Wireless debugging and use Discover or enter port."
            )
            return@withLock false
        }

        _state.value = _state.value.copy(status = Status.CONNECTING, detail = "Connecting to $host:$port ...")
        val created = runCatching { Kadb.create(host, port) }
        val k = created.getOrElse { e ->
            Log.w(TAG, "Create/connect failed", e)
            _state.value = _state.value.copy(status = Status.ERROR, detail = "Connect failed: ${e.message ?: e.javaClass.simpleName}")
            return@withLock false
        }

        val shellOk = runCatching { k.shell("echo bitchord").allOutput.contains("bitchord") }.getOrDefault(false)
        if (!shellOk) {
            runCatching { k.close() }
            _state.value = _state.value.copy(
                status = if (userInitiated) Status.ERROR else Status.PAIRING_REQUIRED,
                detail = "ADB connection refused or unauthorized. Pair again with Wireless debugging pairing code."
            )
            return@withLock false
        }

        kadb = k
        prefs(app).edit().putString(KEY_CONNECT_HOST, host).putInt(KEY_CONNECT_PORT, port).apply()
        _state.value = _state.value.copy(status = Status.READY, detail = "ADB connected to $host:$port", connectHost = host, connectPort = port)
        if (CarBridgeController.isEnabled(app)) {
            CarBridgeRouteManager.apply(app)
        }
        true
    }

    suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val k = kadb ?: return@withLock Result.failure(IllegalStateException("ADB not connected"))
            runCatching {
                val resp = k.shell(command)
                val out = resp.allOutput
                if (resp.exitCode == 0) out else throw IllegalStateException(out.ifBlank { "exit ${resp.exitCode}" })
            }.onFailure {
                Log.w(TAG, "shell failed: $command", it)
                val msg = it.message.orEmpty().lowercase()
                if (msg.contains("closed") || msg.contains("reset") || msg.contains("broken pipe")) {
                    closeKadb()
                    _state.value = _state.value.copy(status = Status.PAIRING_REQUIRED, detail = "ADB connection lost; reconnect required")
                }
            }
        }
    }

    fun close() {
        stopDiscovery()
        closeKadb()
        _state.value = _state.value.copy(status = Status.NOT_READY, detail = "Wireless debugging not connected")
    }

    private fun closeKadb() {
        val k = kadb
        kadb = null
        runCatching { k?.close() }
    }

    private fun ensureCert(context: Context) {
        if (certConfigured) return
        synchronized(this) {
            if (certConfigured) return
            val dir = File(context.filesDir, "adbkeys").apply { mkdirs() }
            val keyFile = File(dir, "bitchord_adbkey")
            val store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath())
            runCatching {
                KadbCert.configure(store = store, policy = KadbCertPolicy())
                certConfigured = true
            }.onFailure { Log.w(TAG, "KadbCert configure failed", it) }
        }
    }

    private fun loadSavedEndpoint(context: Context) {
        _state.value = _state.value.copy(
            connectHost = savedConnectHost(context),
            connectPort = savedConnectPort(context),
            pairPort = savedPairPort(context),
        )
    }

    private fun buildDiscoveryDetail(connectPorts: List<Int>, pairPorts: List<Int>): String {
        val c = if (connectPorts.isEmpty()) "no connect service" else "connect=${connectPorts.joinToString()}"
        val p = if (pairPorts.isEmpty()) "no pairing service" else "pair=${pairPorts.joinToString()}"
        return "Discovered: $c, $p"
    }

    @Suppress("DEPRECATION")
    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("bitchord-adb-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        multicastLock = null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
