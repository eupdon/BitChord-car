package com.music.bitchord.car

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Small overlay shown on top of Android Wireless Debugging pairing screen.
 * This avoids leaving the pairing screen, which can cancel pairing on some devices.
 */
object CarBridgePairingOverlay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: View? = null
    private var wm: WindowManager? = null
    private var statusJob: Job? = null

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val app = context.applicationContext
        if (!canShow(app)) {
            Toast.makeText(app, "Allow 'Display over other apps' for BitChord to show pairing popup.", Toast.LENGTH_LONG).show()
            return
        }
        if (view != null) return

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val density = app.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val padS = (8 * density).toInt()
        val bg = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor("#F21E1E1E".toColorInt())
            setStroke((1 * density).toInt(), "#55FFFFFF".toColorInt())
        }

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String, size: Float = 13f, bold: Boolean = false) = TextView(app).apply {
            this.text = text
            textSize = size
            setTextColor("#FFFFFFFF".toColorInt())
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        val title = label("BitChord pairing code", 15f, bold = true)
        val hint = label("Keep Wireless Debugging pairing screen visible. Enter 6-digit code.", 12f).apply {
            setTextColor("#CCFFFFFF".toColorInt())
        }
        val status = label("Detecting pairing port...", 12f).apply {
            setTextColor("#FFB0E3FF".toColorInt())
        }
        val input = EditText(app).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "123456"
            setHintTextColor("#88FFFFFF".toColorInt())
            setTextColor("#FFFFFFFF".toColorInt())
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor("#33FFFFFF".toColorInt())
                setStroke((1 * density).toInt(), "#55FFFFFF".toColorInt())
            }
            setPadding(padS, padS, padS, padS)
        }

        fun actionButton(text: String): TextView = TextView(app).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#FF111111".toColorInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor("#FFECECEC".toColorInt())
            }
            setPadding(padS, padS, padS, padS)
            gravity = Gravity.CENTER
        }

        val pairBtn = actionButton("Pair")
        val closeBtn = actionButton("Close").apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor("#44FFFFFF".toColorInt())
            }
            setTextColor("#FFFFFFFF".toColorInt())
        }

        val btnRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = padS
        }
        btnRow.addView(closeBtn, btnParams)
        btnRow.addView(pairBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun lp(top: Int = padS) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }

        root.addView(title)
        root.addView(hint, lp((4 * density).toInt()))
        root.addView(status, lp())
        root.addView(input, lp())
        root.addView(btnRow, lp(pad))

        closeBtn.setOnClickListener { hide() }
        pairBtn.setOnClickListener {
            val code = input.text?.toString().orEmpty().trim()
            val port = CarBridgeAdb.state.value.pairPort ?: CarBridgeAdb.state.value.discoveredPairPorts.firstOrNull()
            if (code.length < 6) {
                Toast.makeText(app, "Enter 6-digit pairing code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CarBridgeAdb.pair(app, code, port)
            status.text = "Pairing requested..."
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            (app.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost((360 * density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).toInt()
        }

        runCatching { wm.addView(root, params) }.onSuccess {
            view = root
            statusJob = scope.launch {
                while (view != null) {
                    val s = CarBridgeAdb.state.value
                    val pairPort = s.pairPort ?: s.discoveredPairPorts.firstOrNull()
                    status.text = when {
                        s.status == CarBridgeAdb.Status.PAIRING -> s.detail
                        pairPort != null -> "Detected pairing port: $pairPort"
                        else -> "Waiting for pairing port... Open 'Pair device with pairing code'."
                    }
                    if (s.status == CarBridgeAdb.Status.READY || s.detail.contains("Pairing succeeded", true)) {
                        delay(1200)
                        hide()
                        break
                    }
                    delay(700)
                }
            }
        }.onFailure {
            Toast.makeText(app, "Overlay failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        statusJob?.cancel()
        statusJob = null
        val v = view
        view = null
        val wm = this.wm
        this.wm = null
        if (v != null && wm != null) runCatching { wm.removeView(v) }
    }
}