package com.music.bitchord.car

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

enum class CarBridgeAccessMode(val pref: String) {
    DIRECT_IP("direct_ip"),
    TESLA_LOCAL_DOMAIN("tesla_local_domain");

    companion object {
        fun fromPref(value: String?): CarBridgeAccessMode =
            entries.firstOrNull { it.pref == value } ?: DIRECT_IP
    }
}

object CarBridgeAccess {
    const val PREFS = "car_bridge"
    const val KEY_MODE = "access_mode"
    const val KEY_LOCAL_DOMAIN = "local_domain"
    const val KEY_PUBLIC_IP = "public_ip"
    const val DEFAULT_LOCAL_DOMAIN = "bitchord.car"
    // Assigned to the phone itself by the local VPN. The domain's A record must
    // point here (DNS only, no Cloudflare proxy) so the browser's packets arrive
    // on the phone's hotspot and are delivered to this local address.
    // Link-local is the workaround for Android 13+ ROMs that drop hotspot
    // clients' packets to CGNAT / other off-link destinations.
    const val DEFAULT_PUBLIC_IP = "169.254.88.1"

    fun mode(context: Context): CarBridgeAccessMode =
        CarBridgeAccessMode.fromPref(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: CarBridgeAccessMode) {
        prefs(context).edit().putString(KEY_MODE, mode.pref).apply()
    }

    fun localDomain(context: Context): String =
        prefs(context).getString(KEY_LOCAL_DOMAIN, DEFAULT_LOCAL_DOMAIN)
            ?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_DOMAIN

    fun setLocalDomain(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LOCAL_DOMAIN, value.trim().lowercase()).apply()
    }

    fun publicIp(context: Context): String =
        prefs(context).getString(KEY_PUBLIC_IP, DEFAULT_PUBLIC_IP)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PUBLIC_IP

    fun setPublicIp(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PUBLIC_IP, value.trim()).apply()
    }

    fun accessUrl(context: Context): String = when (mode(context)) {
        CarBridgeAccessMode.DIRECT_IP -> "http://${phoneIp()}:${CarBridgeService.PORT}/"
        CarBridgeAccessMode.TESLA_LOCAL_DOMAIN -> "http://${localDomain(context)}:${CarBridgeService.PORT}/"
    }

    fun notificationText(context: Context): String = when (mode(context)) {
        CarBridgeAccessMode.DIRECT_IP -> accessUrl(context)
        CarBridgeAccessMode.TESLA_LOCAL_DOMAIN ->
            "${accessUrl(context)}  (local IP ${publicIp(context)})"
    }

    /**
     * Address a client on the phone's hotspot can actually reach.
     * Kept here so UI/controller/service share the same decision.
     */
    fun phoneIp(): String {
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList()
            .orEmpty()
            .asSequence()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .toList()

        return addresses.firstOrNull { it == "192.168.137.1" || it == "192.168.43.1" }
            ?: addresses.firstOrNull { it.startsWith("192.168.") }
            ?: addresses.firstOrNull { it.startsWith("172.") }
            ?: addresses.firstOrNull { !it.startsWith("10.") }
            ?: addresses.firstOrNull()
            ?: "192.168.43.1"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
