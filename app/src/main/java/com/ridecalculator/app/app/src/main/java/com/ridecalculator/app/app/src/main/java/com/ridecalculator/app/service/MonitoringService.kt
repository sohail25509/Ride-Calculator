
package com.ridecalculator.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.Image
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager

import com.ridecalculator.app.capture.ScreenCaptureSession
import com.ridecalculator.app.engine.RideDecisionEngine
import com.ridecalculator.app.model.RideOfferData
import com.ridecalculator.app.ocr.OcrTextRecognizer
import com.ridecalculator.app.ocr.RideOfferParser
import com.ridecalculator.app.overlay.OverlayController

class MonitoringService : Service() {

    companion object {
        const val ACTION_START = "com.ridecalculator.app.START_MONITORING"
        const val ACTION_STOP = "com.ridecalculator.app.STOP_MONITORING"

        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "ride_monitoring_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var overlayController: OverlayController? = null
    private var captureSession: ScreenCaptureSession? = null
    private var ocrRecognizer: OcrTextRecognizer? = null

    private val parser = RideOfferParser()
    private val decisionEngine = RideDecisionEngine()

    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_START -> startMonitoring(intent)
            ACTION_STOP -> stopMonitoring()
        }

        return START_NOT_STICKY
    }

    private fun startMonitoring(intent: Intent) {
        if (captureSession != null) return

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            0
        )

        val projectionData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (projectionData == null) {
            Log.e(TAG, "Screen capture permission data missing")
            stopSelf()
            return
        }

        createNotificationChannel()
        startMonitoringForeground()

        val overlay = OverlayController(this)

        if (!overlay.canDrawOverlays() || !overlay.show()) {
            Log.e(TAG, "Overlay permission missing or overlay could not open")
            stopMonitoring()
            return
        }

        overlayController = overlay

        ocrRecognizer = OcrTextRecognizer(
            onTextRecognized = { recognizedText ->
                try {
                    val offer = parser.parse(recognizedText)
                    val result = decisionEngine.evaluate(offer)

                    overlayController?.updateResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Ride parsing failed", e)

                    overlayController?.updateResult(
                        decisionEngine.evaluate(RideOfferData.EMPTY)
                    )
                }
            },
            onError = { error ->
                Log.e(TAG, "OCR error: $error")

                overlayController?.updateResult(
                    decisionEngine.evaluate(RideOfferData.EMPTY)
                )
            }
        )

        val session = ScreenCaptureSession(
            context = this,
            onFrameCaptured = {
                    image: Image,
                    closeImageOnce: () -> Unit,
                    rotationDegrees: Int,
                    frameWidth: Int,
                    frameHeight: Int ->

                val bounds = mapOverlayBounds(
                    overlayController?.getOverlayScreenBounds(),
                    frameWidth,
                    frameHeight
                )

                try {
                    ocrRecognizer?.processImage(
                        image = image,
                        closeImageOnce = closeImageOnce,
                        overlayBounds = bounds,
                        onFinished = {
                            captureSession?.markFrameProcessingComplete()
                        }
                    ) ?: run {
                        closeImageOnce()
                        captureSession?.markFrameProcessingComplete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing failed", e)

                    try {
                        closeImageOnce()
                    } catch (_: Exception) {
                    }

                    captureSession?.markFrameProcessingComplete()
                }
            },
            onProjectionStopped = {
                stopMonitoring()
            }
        )

        captureSession = session

        val started = session.start(
            resultCode,
            projectionData
        )

        if (!started) {
            Log.e(TAG, "Screen capture could not start")
            stopMonitoring()
        }
    }

    private fun mapOverlayBounds(
        screenBounds: Rect?,
        frameWidth: Int,
        frameHeight: Int
    ): Rect? {

        if (screenBounds == null) return null
        if (frameWidth <= 0 || frameHeight <= 0) return null

        val screenWidth: Int
        val screenHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        if (screenWidth <= 0 || screenHeight <= 0) return null

        val scaleX = frameWidth.toFloat() / screenWidth
        val scaleY = frameHeight.toFloat() / screenHeight

        return Rect(
            (screenBounds.left * scaleX).toInt().coerceIn(0, frameWidth),
            (screenBounds.top * scaleY).toInt().coerceIn(0, frameHeight),
            (screenBounds.right * scaleX).toInt().coerceIn(0, frameWidth),
            (screenBounds.bottom * scaleY).toInt().coerceIn(0, frameHeight)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ride Calculator screen monitoring"
            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun startMonitoringForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ride Calculator is monitoring")
            .setContentText("Waiting for an inDrive ride offer")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun stopMonitoring() {
        if (isStopping) return
        isStopping = true

        try {
            captureSession?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Capture stop failed", e)
        }

        captureSession = null

        try {
            ocrRecognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "OCR close failed", e)
        }

        ocrRecognizer = null

        overlayController?.hide()
        overlayController = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
