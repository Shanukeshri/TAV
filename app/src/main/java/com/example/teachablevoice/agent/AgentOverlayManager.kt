package com.example.teachablevoice.agent

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages a translucent overlay drawn on top of the currently active third-party app.
 * This overlay is attached to the AccessibilityService's window, so it floats
 * above Amazon/Chrome/etc. while the agent is working.
 *
 * Shows: "Agent working… Step N / Pause / Stop"
 *
 * Architecture:
 *   AccessibilityService → AgentOverlayManager → translucent panel
 *   AgentOverlayManager observes AgentStateRepository.globalState.stateFlow
 */
class AgentOverlayManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AgentOverlay"
    }

    private var overlayView: View? = null
    private var isShowing = false
    private var observerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Sub-views for updating content
    private var statusText: TextView? = null
    private var stepText: TextView? = null

    /**
     * Starts observing agent state and shows/hides the overlay automatically.
     * Call this from AccessibilityService.onServiceConnected().
     */
    fun startObserving() {
        observerJob?.cancel()
        observerJob = scope.launch {
            AgentStateRepository.globalState.stateFlow.collectLatest { state ->
                if (state.isRunning) {
                    if (!isShowing) {
                        showOverlay()
                    }
                    updateOverlay(state)
                } else {
                    if (isShowing) {
                        // Keep overlay briefly to show final status
                        updateOverlay(state)
                        delay(3000)
                        hideOverlay()
                    }
                }
            }
        }
    }

    /**
     * Stops observing and removes the overlay.
     */
    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        hideOverlay()
    }

    // ──────────────────────────────────────────────
    // Overlay Creation
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
                // Use TYPE_ACCESSIBILITY_OVERLAY — this is the key:
                // Android explicitly allows AccessibilityServices to draw overlays
                // without SYSTEM_ALERT_WINDOW permission
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // Don't intercept touches on the main content area
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alpha = 0.95f
                }
            }

            wm.addView(panel, params)
            isShowing = true

            // Slide-down entrance animation
            panel.translationY = -200f
            panel.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            Log.d(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}", e)
        }
    }

    private fun hideOverlay() {
        if (!isShowing) return

        try {
            val view = overlayView ?: return
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Slide-up exit animation
            view.animate()
                .translationY(-200f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    try {
                        wm.removeView(view)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error removing overlay: ${e.message}")
                    }
                    overlayView = null
                    statusText = null
                    stepText = null
                    isShowing = false
                }
                .start()

            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay: ${e.message}", e)
            isShowing = false
        }
    }

    // ──────────────────────────────────────────────
    // Overlay Layout (programmatic — no XML needed)
    // ──────────────────────────────────────────────

    private fun createOverlayPanel(): View {
        val ctx = service

        // Root container with rounded bottom corners and semi-transparent dark background
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101020"))  // 90% opaque dark blue-black
                cornerRadii = floatArrayOf(
                    0f, 0f,            // top-left
                    0f, 0f,            // top-right
                    dp(16f), dp(16f),  // bottom-right
                    dp(16f), dp(16f)   // bottom-left
                )
            }
            background = bg
            // Extra top padding to avoid the status bar/notch
            setPadding(dp(16f).toInt(), dp(40f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            elevation = dp(8f)
        }

        // ── Row 1: Status indicator + step counter ──
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Pulsing dot
        val dot = View(ctx).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#7C4DFF"))
            }
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply {
                marginEnd = dp(8f).toInt()
            }
            // Pulse animation
            val pulseAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { alpha = it.animatedValue as Float }
            }
            pulseAnimator.start()
        }
        topRow.addView(dot)

        // Status text: "Agent working…" or "DONE ✓"
        statusText = TextView(ctx).apply {
            text = "Agent starting…"
            setTextColor(Color.parseColor("#F0F0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        topRow.addView(statusText)

        // Step counter: "Step 4"
        stepText = TextView(ctx).apply {
            text = "Step 0"
            setTextColor(Color.parseColor("#7C4DFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        topRow.addView(stepText)

        root.addView(topRow)

        // ── Row 2: Stop button ──
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val isTeachMode = AgentStateRepository.globalState.currentGoal?.intent == "TEACH"

        val stopButton = TextView(ctx).apply {
            text = if (isTeachMode) "✔ DONE" else "■ STOP"
            setTextColor(if (isTeachMode) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            val btnBg = GradientDrawable().apply {
                setColor(if (isTeachMode) Color.parseColor("#334CAF50") else Color.parseColor("#33EF5350"))
                cornerRadius = dp(8f)
            }
            background = btnBg
            setPadding(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(6f).toInt())
            setOnClickListener {
                val state = AgentStateRepository.globalState
                if (isTeachMode) {
                    state.statusMessage = "Teaching done"
                    state.appendLog("USER", "Teaching finished by user")
                    state.status = AgentStatus.COMPLETED
                    state.isRunning = false
                } else {
                    state.status = AgentStatus.ERROR
                    state.statusMessage = "Stopped by user"
                    state.isRunning = false
                    state.appendLog("USER", "Agent stopped by user via overlay")
                    kotlin.system.exitProcess(0)
                }
            }
        }
        buttonRow.addView(stopButton)
        root.addView(buttonRow)

        return root
    }

    // ──────────────────────────────────────────────
    // Overlay Updates
    // ──────────────────────────────────────────────

    private fun updateOverlay(state: AgentStateData) {
        val status = state.status
        val step = state.stepCount

        statusText?.text = when (status) {
            AgentStatus.IDLE -> "Agent idle"
            AgentStatus.RUNNING -> "Agent working…"
            AgentStatus.COMPLETED -> "Done ✓"
            AgentStatus.ERROR -> "Error ✗"
            AgentStatus.ASKING -> "Needs input…"
        }

        statusText?.setTextColor(when (status) {
            AgentStatus.RUNNING -> Color.parseColor("#F0F0FF")
            AgentStatus.COMPLETED -> Color.parseColor("#66BB6A")
            AgentStatus.ERROR -> Color.parseColor("#EF5350")
            AgentStatus.ASKING -> Color.parseColor("#FFCA28")
            else -> Color.parseColor("#A0A0C0")
        })

        stepText?.text = "Step $step"
    }

    // ──────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value,
            service.resources.displayMetrics
        )
    }
}
