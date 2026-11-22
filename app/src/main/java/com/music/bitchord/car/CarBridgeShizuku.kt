package com.music.bitchord.car

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Optional privileged bridge for Tesla Local Domain mode.
 *
 * The browser can only open public-looking hosts; we map that host to the phone
 * via local DNS + iptables rules installed through Shizuku (ADB/root shell).
 * Without Shizuku, the normal direct-IP car bridge still works.
 */
object CarBridgeShizuku {

    enum class Status { NOT_INSTALLED, STOPPED, PERMISSION_REQUIRED, READY, ERROR }

    data class State(
        val status: Status = Status.NOT_INSTALLED,
        val detail: String = "Shizuku not detected",
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var binderReady = false
    private var installed = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { onBinderChanged() }
    private val binderDead = Shizuku.OnBinderDeadListener { onBinderChanged() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> onBinderChanged() }

    @Synchronized
    fun init(context: Context) {
        if (binderReady) return
        installed = isInstalled(context)
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
        binderReady = true
        refresh(context)
    }

    fun refresh(context: Context) {
        init(context)
        val app = context.applicationContext
        if (!installed && !isInstalled(app)) {
            _state.value = State(Status.NOT_INSTALLED, "Install and start Shizuku, then grant permission")
            return
        }
        installed = true
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = State(Status.STOPPED, "Shizuku is installed but not running")
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            _state.value = State(Status.PERMISSION_REQUIRED, "Shizuku permission required")
            return
        }
        _state.value = State(Status.READY, "Shizuku ready")
        if (CarBridgeController.isEnabled(app)) {
            scope.launch { CarBridgeRouteManager.apply(app) }
        }
    }

    fun requestPermission() {
        if (!binderReady) return
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = State(Status.STOPPED, "Start Shizuku first")
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            _state.value = State(Status.READY, "Shizuku ready")
            return
        }
        runCatching { Shizuku.requestPermission(8777) }
            .onFailure { _state.value = State(Status.ERROR, it.message ?: "Permission request failed") }
    }

    fun isReady(): Boolean = _state.value.status == Status.READY

    private fun onBinderChanged() {
        main.post {
            // Best effort: no context captured; status will be refreshed by UI/service.
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val granted = alive && runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            _state.value = when {
                !installed && !alive -> State(Status.NOT_INSTALLED, "Install and start Shizuku, then grant permission")
                !alive -> State(Status.STOPPED, "Shizuku is installed but not running")
                !granted -> State(Status.PERMISSION_REQUIRED, "Shizuku permission required")
                else -> State(Status.READY, "Shizuku ready")
            }
        }
    }

    private fun isInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
}
