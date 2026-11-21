
package com.ridecalculator.app.overlay
import android.graphics.Rect
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.LinearLayout
import android.widget.TextView
import com.ridecalculator.app.R
import com.ridecalculator.app.model.RideEvaluationResult
import com.ridecalculator.app.model.RideVerdict
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Controls the compact, draggable, read-only floating overlay card rendered above other apps.
 *
 * Touch & boundary behavior:
 * - Uses TYPE_APPLICATION_OVERLAY for Android 8 through Android 15.
 * - Touches outside the card pass through to inDrive.
 * - Touches inside the card are used only to drag the overlay.
 * - Enforces a 250dp maximum card width.
 * - Never uses AccessibilityService, simulated taps, or automated ride acceptance.
 */
class OverlayController(private val context: Context) {

    companion object {
        private const val MAX_CARD_WIDTH_DP = 250
        private const val EDGE_MARGIN_DP = 8
        private const val INITIAL_X_DP = 16
        private const val INITIAL_Y_DP = 96

        private val COLOR_GREEN_UTHAA_LO = Color.parseColor("#15803D")
        private val COLOR_RED_MAT_UTHAO = Color.parseColor("#B91C1C")
        private val COLOR_NEUTRAL_NOT_FOUND = Color.parseColor("#374151")
    }

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    private var cardRoot: LinearLayout? = null
    private var tvDragHint: TextView? = null
    private var tvVerdict: TextView? = null
    private var tvRatePerKm: TextView? = null
    private var tvDetails: TextView? = null
    private var tvOcrDebug: TextView? = null

    /**
     * Returns true if the user has granted "Display over other apps" permission.
     */
    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /**
     * Callers MUST invoke show() on the main thread.
     * If called from a background thread, this method returns false immediately
     * without posting any deferred attachment task.
     */
    fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false
        }

        if (!canDrawOverlays()) {
            return false
        }

        val existingView = overlayView
        if (isShowing && existingView != null && existingView.isAttachedToWindow) {
            return true
        }

        return try {
            val themedContext = ContextThemeWrapper(appContext, R.style.Theme_RideCalculator)
            val view = LayoutInflater.from(themedContext)
                .inflate(R.layout.view_floating_overlay, null, false)

            val root = view.findViewById<LinearLayout>(R.id.overlayCardRoot)
            val dragHint = view.findViewById<TextView>(R.id.tvDragHint)
            val verdict = view.findViewById<TextView>(R.id.tvVerdict)
            val ratePerKm = view.findViewById<TextView>(R.id.tvRatePerKm)
            val details = view.findViewById<TextView>(R.id.tvDetails)
            val ocrDebug = view.findViewById<TextView>(R.id.tvOcrDebug)

            cardRoot = root
            tvDragHint = dragHint
            tvVerdict = verdict
            tvRatePerKm = ratePerKm
            tvDetails = details
            tvOcrDebug = ocrDebug

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(INITIAL_X_DP)
                y = dpToPx(INITIAL_Y_DP)
            }

            enforceMaxWidthAndClampPosition(view, params, params.x, params.y)
            attachDragListener(view, params)

            windowManager.addView(view, params)

            overlayView = view
            layoutParams = params
            isShowing = true

            view.post {
                if (isShowing && overlayView === view) {
                    enforceMaxWidthAndClampPosition(view, params, params.x, params.y)
                    safeUpdateLayout(view, params)
                }
            }

            applyNeutralInitialState()
            true
        } catch (_: Exception) {
            cleanupReferences()
            false
        }
    }

    /**
     * Updates the overlay card with the latest evaluation result.
     */
    fun updateResult(result: RideEvaluationResult) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateResult(result) }
            return
        }

        val view = overlayView
        val params = layoutParams
        if (!isShowing || view == null || params == null) {
            return
        }

        val verdictView = tvVerdict ?: return
        val rateView = tvRatePerKm ?: return
        val detailsView = tvDetails ?: return

        verdictView.text = result.verdict.displayText
        tvDragHint?.text = "DRAG TO MOVE"

        when (result.verdict) {
            RideVerdict.UTHAA_LO -> setCardBackgroundColor(COLOR_GREEN_UTHAA_LO)
            RideVerdict.MAT_UTHAO -> setCardBackgroundColor(COLOR_RED_MAT_UTHAO)
            RideVerdict.DATA_NOT_FOUND -> setCardBackgroundColor(COLOR_NEUTRAL_NOT_FOUND)
        }

        val offer = result.offer
        val rateText = result.ratePerKm?.let { rate ->
            String.format(Locale.US, "Rs. %.1f / km", rate)
        } ?: "Rate: -- Rs./km"
        rateView.text = rateText

        val farePart = offer.farePkr?.let {
            String.format(Locale.US, "Fare: Rs. %.0f", it)
        } ?: "Fare: --"

        val pickupPart = offer.pickupDistanceKm?.let {
            String.format(Locale.US, "Pickup: %.2f km", it)
        } ?: "Pickup: --"

        val destPart = offer.destinationTripDistanceKm?.let {
            String.format(Locale.US, "Trip: %.2f km", it)
        } ?: "Trip: --"

        detailsView.text = "$farePart\n$pickupPart  |  $destPart"

        view.post {
            if (isShowing && overlayView === view && layoutParams === params) {
                enforceMaxWidthAndClampPosition(view, params, params.x, params.y)
                safeUpdateLayout(view, params)
            }
        }
    }

    /**
     * Shows only fare/distance-like OCR tokens; personal text is not displayed.
     */
    fun updateOcrDebug(recognizedText: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateOcrDebug(recognizedText) }
            return
        }

        val acceptedFare = Regex(
            """(?i)accept\s+for\s+(?:rs\.?|pkr)\s*[0-9][0-9,]*(?:\.[0-9]+)?"""
        ).find(recognizedText)?.value?.replace(Regex("""\s+"""), " ")
        val distances = Regex(
            """(?i)[0-9]+(?:[.,][0-9]+)?\s*(?:km|kms|kilometers?|kilometres?|m|meters?|metres?)\b"""
        ).findAll(recognizedText)
            .map { it.value.trim().replace(Regex("""\s+"""), " ") }
            .distinct()
            .take(3)
            .toList()
        val fareFallback = if (acceptedFare == null) {
            Regex("""(?i)(?:rs\.?|pkr)\s*[0-9][0-9,]*(?:\.[0-9]+)?""")
                .find(recognizedText)?.value
        } else null
        val tokens = listOfNotNull(acceptedFare ?: fareFallback) + distances

        tvOcrDebug?.text = if (tokens.isEmpty()) {
            val safeLines = recognizedText.lines()
                .map { line ->
                    line.replace(Regex("""[A-Za-z]+"""), "X")
                        .replace(Regex("""\\d{5,}"""), "[number]")
                        .trim()
                }
                .filter { it.isNotBlank() }
                .take(4)
                .joinToString(" / ")
                .take(120)
            if (safeLines.isBlank()) {
                "OCR: no fare/distance text (" + recognizedText.length + " chars)"
            } else {
                "OCR masked: " + safeLines
            }
        } else {
            "OCR: " + tokens.joinToString(" | ")
        }
    }

    /**
     * Safely detaches and cleans up the floating overlay window.
     */
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }

        val view = overlayView
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                } else {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
                // Ignore if window token was already invalidated by the system.
            }
        }
        cleanupReferences()
    }

    private fun applyNeutralInitialState() {
        setCardBackgroundColor(COLOR_NEUTRAL_NOT_FOUND)
        tvDragHint?.text = "DRAG TO MOVE"
        tvVerdict?.text = RideVerdict.DATA_NOT_FOUND.displayText
        tvRatePerKm?.text = "Rate: -- Rs./km"
        tvDetails?.text = "Monitoring screen...\nOpen inDrive ride offer"
    }

    private fun setCardBackgroundColor(color: Int) {
        val bg = cardRoot?.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            cardRoot?.setBackgroundColor(color)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).roundToInt()
                    val deltaY = (event.rawY - initialTouchY).roundToInt()
                    enforceMaxWidthAndClampPosition(
                        view = view,
                        params = params,
                        desiredX = initialX + deltaX,
                        desiredY = initialY + deltaY
                    )
                    safeUpdateLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun enforceMaxWidthAndClampPosition(
        view: View,
        params: WindowManager.LayoutParams,
        desiredX: Int,
        desiredY: Int
    ) {
        val screenBounds = getCurrentScreenBounds()
        val marginPx = dpToPx(EDGE_MARGIN_DP)
        val maxCardWidthPx = dpToPx(MAX_CARD_WIDTH_DP)

        val availableScreenWidth =
            (screenBounds.width() - (marginPx * 2)).coerceAtLeast(dpToPx(120))
        val effectiveMaxWidth = minOf(maxCardWidthPx, availableScreenWidth)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            effectiveMaxWidth,
            View.MeasureSpec.AT_MOST
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0,
            View.MeasureSpec.UNSPECIFIED
        )
        view.measure(widthSpec, heightSpec)

        val cardWidth = view.measuredWidth.coerceIn(1, effectiveMaxWidth)
        val cardHeight = view.measuredHeight.coerceAtLeast(1)

        if (params.width != cardWidth) {
            params.width = cardWidth
        }
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        val minX = marginPx
        val maxX = (screenBounds.width() - cardWidth - marginPx).coerceAtLeast(minX)

        val minY = marginPx
        val maxY = (screenBounds.height() - cardHeight - marginPx).coerceAtLeast(minY)

        params.x = desiredX.coerceIn(minX, maxX)
        params.y = desiredY.coerceIn(minY, maxY)
    }

    private fun getCurrentScreenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Rect(0, 0, bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val outRect = Rect()
            @Suppress("DEPRECATION")
            display.getRectSize(outRect)
            Rect(0, 0, outRect.width(), outRect.height())
        }
    }

    private fun safeUpdateLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (isShowing && view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (_: Exception) {
            // Safe guard against window teardown races.
        }
    }

    private fun cleanupReferences() {
        overlayView = null
        layoutParams = null
        cardRoot = null
        tvDragHint = null
        tvVerdict = null
        tvRatePerKm = null
        tvDetails = null
        tvOcrDebug = null
        isShowing = false
    }

    private fun dpToPx(dp: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }
    fun getOverlayScreenBounds(): Rect? {
        val view = overlayView ?: return null

        if (!view.isAttachedToWindow) {
            return null
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        return Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
    }
}
