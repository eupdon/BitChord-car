package com.music.bitchord.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local VPN DNS helper for Tesla Local Domain mode.
 *
 * Tesla browser blocks RFC1918 destinations. We give it a public-looking domain
 * (e.g. bitchord.car) that resolves to a public-looking IP (e.g. 172.32.0.1).
 * This VpnService intercepts DNS queries for that domain and answers with the
 * configured IP. Actual TCP routing/REDIRECT is applied separately via embedded ADB
 * shell iptables rules in [CarBridgeRouteManager].
 *
 * This is best-effort: user must approve VPN permission once.
 */
class CarBridgeDnsService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var loopJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!CarBridgeController.isEnabled(this) || CarBridgeAccess.mode(this) != CarBridgeAccessMode.TESLA_LOCAL_DOMAIN) {
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        restartTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        running = false
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    private fun shutdown() {
        running = false
        loopJob?.cancel()
        loopJob = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun restartTunnel() {
        loopJob?.cancel()
        runCatching { vpnInterface?.close() }
        vpnInterface = establishInterface() ?: return
        running = true
        val fd = vpnInterface ?: return
        loopJob = scope.launch { runLoop(fd) }
    }

    private fun establishInterface(): ParcelFileDescriptor? {
        val domain = CarBridgeAccess.localDomain(this)
        val ip = CarBridgeAccess.publicIp(this)
        val upstream = findUpstreamDns() ?: listOf("8.8.8.8", "1.1.1.1")
        return runCatching {
            Builder()
                .setSession("BitChord Tesla DNS")
                .setMtu(1500)
                .addAddress("10.215.173.1", 24)
                .apply { upstream.forEach { runCatching { addDnsServer(it) } } }
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
                .also { b ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
                    // Try to keep data path sane; DNS interception is best-effort.
                    runCatching { b.addDisallowedApplication(packageName) }
                }
                .establish()
        }.onFailure {
            android.util.Log.w(TAG, "VPN establish failed", it)
        }.getOrNull()
    }

    private suspend fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val packet = ByteArray(32767)
        while (scope.isActive) {
            val len = runCatching { input.read(packet) }.getOrDefault(-1)
            if (len <= 0) {
                if (len < 0) break
                continue
            }
            val response = handlePacket(packet, len) ?: continue
            runCatching { output.write(response) }
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int): ByteArray? {
        if (len < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null // MVP: IPv4 DNS only
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        val proto = packet[9].toInt() and 0xFF
        if (proto != 17) return null
        val udpOffset = ihl
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = readU16(packet, udpOffset)
        val dstPort = readU16(packet, udpOffset + 2)
        val udpLen = readU16(packet, udpOffset + 4)
        if (dstPort != 53) return null
        val dnsOffset = udpOffset + 8
        val dnsLen = len - dnsOffset
        if (dnsLen <= 0) return null
        val dns = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)

        val qname = parseQName(dns) ?: return forwardDns(packet, len, dns, dstIp, srcIp, srcPort, dstPort)
        val domain = CarBridgeAccess.localDomain(this).trim().lowercase().trimEnd('.')
        if (qname != domain) {
            return forwardDns(packet, len, dns, dstIp, srcIp, srcPort, dstPort)
        }
        val answerIp = runCatching { InetAddress.getByName(CarBridgeAccess.publicIp(this)) }.getOrNull() as? Inet4Address
            ?: return forwardDns(packet, len, dns, dstIp, srcIp, srcPort, dstPort)
        val dnsResp = buildDnsResponse(dns, answerIp) ?: return null
        return buildUdpIpv4Response(dstIp, srcIp, 53, srcPort, dnsResp)
    }

    private fun forwardDns(orig: ByteArray, origLen: Int, dns: ByteArray, dnsServer: ByteArray, clientIp: ByteArray, clientPort: Int, serverPort: Int): ByteArray? {
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                val upstream = InetAddress.getByAddress(dnsServer)
                socket.send(DatagramPacket(dns, dns.size, upstream, 53))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                buildUdpIpv4Response(dnsServer, clientIp, 53, clientPort, buf.copyOf(resp.length))
            }
        }.getOrNull()
    }

    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 12) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0) return null // compressed qname not expected in query
            pos++
            if (pos + len > dns.size) return null
            labels += String(dns, pos, len)
            pos += len
        }
        return labels.joinToString(".").lowercase()
    }

    private fun buildDnsResponse(query: ByteArray, ip: Inet4Address): ByteArray? {
        if (query.size < 12) return null
        val qdcount = readU16(query, 4)
        if (qdcount != 1) return null
        var pos = 12
        while (pos < query.size) {
            val l = query[pos].toInt() and 0xFF
            pos++
            if (l == 0) break
            pos += l
            if (pos > query.size) return null
        }
        if (pos + 4 > query.size) return null
        val qtype = readU16(query, pos)
        val qclass = readU16(query, pos + 2)
        pos += 4
        if (pos > query.size) return null

        val header = ByteArray(12)
        header[0] = query[0]; header[1] = query[1] // id
        header[2] = 0x81.toByte() // QR, RD
        header[3] = if (qtype == 1 && qclass == 1) 0x80.toByte() else 0x80.toByte() // RA, NOERROR (NX for non-A handled as no answer below)
        writeU16(header, 4, 1)
        writeU16(header, 6, if (qtype == 1 && qclass == 1) 1 else 0)
        writeU16(header, 8, 0)
        writeU16(header, 10, 0)

        val question = query.copyOfRange(12, pos)
        if (qtype != 1 || qclass != 1) {
            return header + question
        }
        val answer = ByteBuffer.allocate(16)
        answer.put(0xC0.toByte()); answer.put(0x0C.toByte()) // pointer to qname
        answer.putShort(1) // A
        answer.putShort(1) // IN
        answer.putInt(60)  // TTL
        answer.putShort(4)
        answer.put(ip.address)
        return header + question + answer.array()
    }

    private fun buildUdpIpv4Response(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val ihl = 20
        val udpLen = 8 + payload.size
        val totalLen = ihl + udpLen
        val buf = ByteArray(totalLen)
        buf[0] = 0x45.toByte()
        buf[1] = 0
        writeU16(buf, 2, totalLen)
        writeU16(buf, 4, 0)
        writeU16(buf, 6, 0)
        buf[8] = 64
        buf[9] = 17
        writeU16(buf, 10, 0)
        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(dstIp, 0, buf, 16, 4)
        writeU16(buf, 10, ipv4Checksum(buf, 0, ihl))

        writeU16(buf, ihl, srcPort)
        writeU16(buf, ihl + 2, dstPort)
        writeU16(buf, ihl + 4, udpLen)
        writeU16(buf, ihl + 6, 0)
        System.arraycopy(payload, 0, buf, ihl + 8, payload.size)
        return buf
    }

    private fun ipv4Checksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len) {
            if (i == offset + 10) { i += 2; continue }
            val v = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += v
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readU16(buf: ByteArray, off: Int): Int = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun findUpstreamDns(): List<String>? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val link = cm.getLinkProperties(active) ?: return null
        return link.dnsServers.mapNotNull { it.hostAddress }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    private fun buildNotification(): Notification {
        val channelId = "car_bridge_dns"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Car Bridge DNS", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val stop = PendingIntent.getService(
            this, 21, Intent(this, CarBridgeDnsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val domain = CarBridgeAccess.localDomain(this)
        val ip = CarBridgeAccess.publicIp(this)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BitChord Tesla DNS")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
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
