
package com.ridecalculator.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ridecalculator.app.capture.ScreenCaptureSession
import com.ridecalculator.app.engine.RideDecisionEngine
import com.ridecalculator.app.ocr.OcrTextRecognizer
import com.ridecalculator.app.ocr.RideOfferParser
import com.ridecalculator.app.overlay.OverlayController

class MonitoringService : Service() {

    companion object {
        const val ACTION_START = "com.ridecalculator.app.START_MONITORING"
        const val ACTION_STOP = "com.ridecalculator.app.STOP_MONITORING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "ride_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MonitoringService"
    }

    private var captureSession: ScreenCaptureSession? = null
    private var ocrRecognizer: OcrTextRecognizer? = null
    private var overlayController: OverlayController? = null

    private val parser = RideOfferParser()
    private val decisionEngine = RideDecisionEngine()

    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startMonitoring(resultCode, resultData)
            }

            ACTION_STOP -> stopMonitoring()
        }

        return START_NOT_STICKY
    }

    private fun startMonitoring(
        resultCode: Int,
        resultData: Intent
    ) {
        if (captureSession != null) return

        createNotificationChannel()

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val controller = OverlayController(this)
        overlayController = controller

        if (!controller.show()) {
            Log.e(TAG, "Overlay could not be displayed")
        }

        ocrRecognizer = OcrTextRecognizer(
            onTextRecognized = { recognizedText ->
                processRecognizedText(recognizedText)
            },
            onError = { error ->
                Log.e(TAG, "OCR error: $error")
            }
        )

        val session = ScreenCaptureSession(
            context = this,
            onFrameCaptured = {
                    image,
                    closeImageOnce,
                    rotationDegrees,
                    frameWidth,
                    frameHeight ->

                val bounds = overlayController?.getOverlayScreenBounds()

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
            },
            onProjectionStopped = {
                stopMonitoring()
            }
        )

        captureSession = session

        val started = session.start(resultCode, resultData)

        if (!started) {
            Log.e(TAG, "Screen capture could not start")
            stopMonitoring()
        }
    }

    private fun processRecognizedText(text: String) {
        val offer = parser.parse(text)
        val result = decisionEngine.evaluate(offer)

        overlayController?.updateResult(result)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Ride Calculator")
            .setContentText("Ride monitoring is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ride Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun stopMonitoring() {
        if (isStopping) return
        isStopping = true

        try {
            captureSession?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Capture stop error", e)
        }

        captureSession = null

        try {
            ocrRecognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "OCR close error", e)
        }

        ocrRecognizer = null

        try {
            overlayController?.hide()
        } catch (e: Exception) {
            Log.e(TAG, "Overlay hide error", e)
        }

        overlayController = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
