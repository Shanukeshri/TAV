package com.example.teachablevoice.voice

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.teachablevoice.agent.AgentForegroundService
import com.example.teachablevoice.agent.AgentStateRepository
import com.example.teachablevoice.agent.AgentStatus
import com.example.teachablevoice.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages the voice listening overlay shown on top of any app.
 *
 * This overlay is attached to the AccessibilityService window, so it floats
 * above everything without needing SYSTEM_ALERT_WINDOW. It provides:
 *
 *   1. A "🎤 Listening..." indicator when the wake phrase activates STT
 *   2. Live partial transcription feedback
 *   3. "⏳ Processing..." when routing through Gemini
 *   4. Seamless handoff to the existing AgentOverlayManager once the agent starts
 *
 * Architecture:
 *   MirrorAccessibilityService
 *     → PersistentVoiceListener (always-on wake word STT)
 *       → wake phrase detected → VoiceListeningOverlayManager.show()
 *       → command captured → route through Gemini → launch agent
 *       → AgentOverlayManager takes over (existing overlay)
 *
 * The voice overlay uses TYPE_ACCESSIBILITY_OVERLAY, the same mechanism as
 * AgentOverlayManager, so no extra permissions are needed.
 */
class VoiceListeningOverlayManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "VoiceOverlay"
    }

    private var overlayView: View? = null
    private var isShowing = false
    private val handler = Handler(Looper.getMainLooper())

    // Sub-views for dynamic updates
    private var statusText: TextView? = null
    private var transcriptionText: TextView? = null
    private var dot: View? = null

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /** Show the overlay indicating listening state. */
    fun showListening() {
        handler.post {
            if (!isShowing) {
                showOverlay()
            }
            updateState("🎤  Listening...", "", Color.parseColor("#66BB6A"))
        }
    }

    /** Update with partial transcription while user is speaking. */
    fun updatePartialText(partial: String) {
        handler.post {
            transcriptionText?.text = "\"$partial\""
            transcriptionText?.visibility = if (partial.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Show processing state after speech is captured. */
    fun showProcessing(transcribedText: String) {
        handler.post {
            updateState("⏳  Processing...", "\"$transcribedText\"", Color.parseColor("#448AFF"))
        }
    }

    /** Show routing result before agent takes over. */
    fun showRouting(targetApp: String, task: String) {
        handler.post {
            updateState("🚀  Opening $targetApp...", task, Color.parseColor("#7C4DFF"))
        }
    }

    /** Show error state. */
    fun showError(message: String) {
        handler.post {
            updateState("❌  Error", message, Color.parseColor("#EF5350"))
            // Auto-hide after 3 seconds
            handler.postDelayed({ hide() }, 3000)
        }
    }

    /** Hide the overlay. */
    fun hide() {
        handler.post { hideOverlay() }
    }

    /** Hide after a delay (e.g., when handing off to agent overlay). */
    fun hideAfterDelay(delayMs: Long = 1500) {
        handler.postDelayed({ hideOverlay() }, delayMs)
    }

    // ──────────────────────────────────────────────
    // Overlay lifecycle
    // ──────────────────────────────────────────────

    private fun showOverlay() {
        if (isShowing) return

        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val panel = createOverlayPanel()
            overlayView = panel

            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alpha = 0.95f
                }
            }

            wm.addView(panel, params)
            isShowing = true

            // Slide-down entrance from top
            panel.translationY = -200f
            panel.animate()
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()

            Log.d(TAG, "Voice overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show voice overlay: ${e.message}", e)
        }
    }

    private fun hideOverlay() {
        if (!isShowing) return

        try {
            val view = overlayView ?: return
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            view.animate()
                .translationY(-200f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    try {
                        wm.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error removing voice overlay: ${e.message}")
                    }
                    overlayView = null
                    statusText = null
                    transcriptionText = null
                    dot = null
                    isShowing = false
                }
                .start()

            Log.d(TAG, "Voice overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide voice overlay: ${e.message}")
            isShowing = false
        }
    }

    private fun updateState(status: String, detail: String, color: Int) {
        statusText?.text = status
        statusText?.setTextColor(color)

        if (detail.isNotEmpty()) {
            transcriptionText?.text = detail
            transcriptionText?.visibility = View.VISIBLE
        } else {
            transcriptionText?.visibility = View.GONE
        }

        // Update dot color
        (dot?.background as? GradientDrawable)?.setColor(color)
    }

    // ──────────────────────────────────────────────
    // Layout
    // ──────────────────────────────────────────────

    private fun createOverlayPanel(): View {
        val ctx = service

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6080818"))  // dark translucent
                cornerRadii = floatArrayOf(
                    0f, 0f,             // top-left
                    0f, 0f,             // top-right
                    dp(20f), dp(20f),   // bottom-right
                    dp(20f), dp(20f)    // bottom-left
                )
            }
            background = bg
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            elevation = dp(8f)
        }

        // ── Row 1: dot + status text ──
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Pulsing dot
        dot = View(ctx).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66BB6A"))
            }
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(10f).toInt(), dp(10f).toInt()).apply {
                marginEnd = dp(10f).toInt()
            }
            val pulse = ValueAnimator.ofFloat(0.3f, 1f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { alpha = it.animatedValue as Float }
            }
            pulse.start()
        }
        topRow.addView(dot)

        statusText = TextView(ctx).apply {
            text = "🎤  Listening..."
            setTextColor(Color.parseColor("#66BB6A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        topRow.addView(statusText)
        root.addView(topRow)

        // ── Row 2: transcription text ──
        transcriptionText = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#C0C0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            maxLines = 2
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6f).toInt()
            }
        }
        root.addView(transcriptionText)

        return root
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value,
            service.resources.displayMetrics
        )
    }
}
