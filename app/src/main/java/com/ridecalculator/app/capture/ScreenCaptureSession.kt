
package com.ridecalculator.app.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages user-authorized MediaProjection screen capture and throttled local frame delivery.
 *
 * Caller contract:
 * - When onFrameCaptured is invoked, the caller owns image and MUST invoke closeImageOnce()
 *   once pixel extraction completes (even on error).
 * - For every frame delivered to onFrameCaptured, the caller MUST call
 *   markFrameProcessingComplete() once processing finishes.
 */
class ScreenCaptureSession(
    context: Context,
    private val minFrameIntervalMs: Long = DEFAULT_MIN_FRAME_INTERVAL_MS,
    private val onFrameCaptured: (
        image: Image,
        closeImageOnce: () -> Unit,
        rotationDegrees: Int,
        frameWidth: Int,
        frameHeight: Int
    ) -> Unit,
    private val onProjectionStopped: () -> Unit = {}
) {

    companion object {
        const val DEFAULT_MIN_FRAME_INTERVAL_MS = 1500L
        private const val VIRTUAL_DISPLAY_NAME = "RideCalculatorCapture"

        fun createConsentIntent(context: Context): Intent {
            val manager =
                context.applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            return manager.createScreenCaptureIntent()
        }
    }

    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lifecycleLock = Any()

    private var sessionGeneration: Long = 0L
    private var activeSessionId: Long = 0L
    private var handlerThread: HandlerThread? = null
    private var mediaProjection: MediaProjection? = null
    private var activeProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    @Volatile
    private var captureWidth: Int = 0

    @Volatile
    private var captureHeight: Int = 0

    private var lastProcessedTimestampMs: Long = 0L
    private val isProcessingFrame = AtomicBoolean(false)
    private var isSessionActive = false
    private var isStarting = false
    private var isStopping = false
    private var inFlightOperations = 0
    private var projectionStoppedBySystem = false
    private var pendingCapturedContentSize: Pair<Int, Int>? = null

    fun start(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) return false

        val sessionId: Long

        synchronized(lifecycleLock) {
            if (isStarting || isStopping || isSessionActive || inFlightOperations > 0) {
                return false
            }

            sessionGeneration += 1L
            sessionId = sessionGeneration
            activeSessionId = sessionId
            isStarting = true
            isStopping = false
            isSessionActive = false
            projectionStoppedBySystem = false
            pendingCapturedContentSize = null
            inFlightOperations = 1
            lastProcessedTimestampMs = 0L
            isProcessingFrame.set(false)
        }

        var thread: HandlerThread? = null
        var projection: MediaProjection? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var callback: MediaProjection.Callback? = null
        var registeredCallbackOnProjection = false
        var shouldReleaseAfterSetup = false
        var setupSucceeded = false

        try {
            val metrics = resolveCaptureMetrics()

            thread = HandlerThread("RideCaptureThread-$sessionId")
            thread.start()

            val handler = Handler(thread.looper)

            val createdCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handleSystemProjectionStop(sessionId)
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        handleCapturedContentResize(sessionId, width, height)
                    }
                }
            }
            callback = createdCallback

            synchronized(lifecycleLock) {
                if (activeSessionId != sessionId || isStopping || projectionStoppedBySystem) {
                    shouldReleaseAfterSetup = true
                    return false
                }

                captureWidth = metrics.width
                captureHeight = metrics.height
                handlerThread = thread
                activeProjectionCallback = createdCallback
            }

            projection = projectionManager.getMediaProjection(resultCode, data)

            if (projection == null) {
                shouldReleaseAfterSetup = true
                return false
            }

            synchronized(lifecycleLock) {
                if (activeSessionId != sessionId || isStopping || projectionStoppedBySystem) {
                    shouldReleaseAfterSetup = true
                    return false
                }

                projection.registerCallback(createdCallback, handler)
                registeredCallbackOnProjection = true
            }

            synchronized(lifecycleLock) {
                if (activeSessionId != sessionId || isStopping || projectionStoppedBySystem) {
                    shouldReleaseAfterSetup = true
                    return false
                }
            }

            @SuppressLint("WrongConstant")
            reader = ImageReader.newInstance(
                metrics.width,
                metrics.height,
                PixelFormat.RGBA_8888,
                2
            )

            val boundReader = reader

            boundReader.setOnImageAvailableListener({ readerRef ->
                handleImageAvailable(sessionId, readerRef)
            }, handler)

            val surface: Surface = boundReader.surface

            display = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                metrics.width,
                metrics.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )

            if (display == null) {
                shouldReleaseAfterSetup = true
                return false
            }

            val pendingResize = synchronized(lifecycleLock) {
                if (activeSessionId != sessionId || isStopping || projectionStoppedBySystem) {
                    shouldReleaseAfterSetup = true
                    null
                } else {
                    mediaProjection = projection
                    activeProjectionCallback = createdCallback
                    imageReader = boundReader
                    virtualDisplay = display
                    isSessionActive = true
                    isStarting = false
                    val pending = pendingCapturedContentSize
                    pendingCapturedContentSize = null
                    pending
                }
            }

            if (shouldReleaseAfterSetup) return false

            setupSucceeded = true
            if (pendingResize != null) {
                handleCapturedContentResize(sessionId, pendingResize.first, pendingResize.second)
            }
            return true

        } catch (_: Throwable) {
            shouldReleaseAfterSetup = true
            return false

        } finally {
            var finishTeardownNow = false
            var notifySystemStopNow = false

            synchronized(lifecycleLock) {
                inFlightOperations--

                if (!setupSucceeded && activeSessionId == sessionId) {
                    isStarting = false
                    isSessionActive = false

                    isStopping = shouldReleaseAfterSetup || projectionStoppedBySystem
                }

                if (activeSessionId == sessionId && isStopping && inFlightOperations == 0) {
                    finishTeardownNow = true

                    if (projectionStoppedBySystem) {
                        projectionStoppedBySystem = false
                        notifySystemStopNow = true
                    }
                }
            }

            if (shouldReleaseAfterSetup) {
                val releaseNow = synchronized(lifecycleLock) {
                    activeSessionId == sessionId && inFlightOperations == 0
                }

                if (releaseNow) {
                    val callbackToUnregister = synchronized(lifecycleLock) {
                        if (activeSessionId == sessionId && registeredCallbackOnProjection) {
                            activeProjectionCallback ?: callback
                        } else {
                            null
                        }
                    }

                    safeReleaseComponents(
                        display = display,
                        reader = reader,
                        projection = projection,
                        callback = callbackToUnregister,
                        thread = thread,
                        unregisterCallback = projection != null && callbackToUnregister != null
                    )

                    var notifyFromSetupRelease = false

                    synchronized(lifecycleLock) {
                        if (activeSessionId == sessionId) {
                            clearSessionFieldsLocked()
                            isStopping = false
                            isStarting = false
                            isSessionActive = false
                            isProcessingFrame.set(false)

                            if (projectionStoppedBySystem) {
                                projectionStoppedBySystem = false
                                notifyFromSetupRelease = true
                            }
                        }
                    }

                    if (notifyFromSetupRelease) {
                        notifyProjectionStoppedOnMainThread()
                    }
                }

            } else if (finishTeardownNow) {
                performTeardownIfIdle(
                    expectedSessionId = sessionId,
                    notifySystemStop = notifySystemStopNow
                )
            }
        }
    }

    fun markFrameProcessingComplete() {
        isProcessingFrame.set(false)
    }

    fun stop() {
        val currentId: Long

        synchronized(lifecycleLock) {
            currentId = activeSessionId

            if (
                currentId == 0L ||
                (!isSessionActive && !isStarting && inFlightOperations == 0)
            ) {
                return
            }
        }

        beginStopForSession(
            sessionId = currentId,
            isSystemStop = false
        )
    }

    private fun handleSystemProjectionStop(sessionId: Long) {
        synchronized(lifecycleLock) {
            if (activeSessionId != sessionId) return
            projectionStoppedBySystem = true
        }

        beginStopForSession(
            sessionId = sessionId,
            isSystemStop = true
        )
    }

    private fun beginStopForSession(
        sessionId: Long,
        isSystemStop: Boolean
    ) {
        val shouldPerformNow: Boolean
        val notifyImmediately: Boolean

        synchronized(lifecycleLock) {
            if (
                activeSessionId != sessionId ||
                (!isSessionActive && !isStarting && inFlightOperations == 0)
            ) {
                return
            }

            if (isSystemStop) {
                projectionStoppedBySystem = true
            }

            isStarting = false
            isSessionActive = false
            isStopping = true
            isProcessingFrame.set(false)

            shouldPerformNow = inFlightOperations == 0
            notifyImmediately = shouldPerformNow && projectionStoppedBySystem

            if (notifyImmediately) {
                projectionStoppedBySystem = false
            }
        }

        if (shouldPerformNow) {
            performTeardownIfIdle(
                expectedSessionId = sessionId,
                notifySystemStop = notifyImmediately
            )
        }
    }

    private fun handleImageAvailable(
        sessionId: Long,
        reader: ImageReader
    ) {
        val acquiredLock = synchronized(lifecycleLock) {
            if (
                activeSessionId != sessionId ||
                !isSessionActive ||
                isStopping ||
                imageReader !== reader
            ) {
                false
            } else {
                inFlightOperations++
                true
            }
        }

        if (!acquiredLock) {
            drainAndCloseSingleImage(reader)
            return
        }

        var rawImage: Image? = null
        val closedFlag = AtomicBoolean(false)

        val closeOnce: () -> Unit = {
            if (closedFlag.compareAndSet(false, true)) {
                try {
                    rawImage?.close()
                } catch (_: Exception) {
                }
            }
        }

        var handedOffToCaller = false

        try {
            val active = synchronized(lifecycleLock) {
                activeSessionId == sessionId &&
                    isSessionActive &&
                    !isStopping &&
                    imageReader === reader
            }

            if (!active) {
                drainAndCloseSingleImage(reader)
                isProcessingFrame.set(false)
                return
            }

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastProcessedTimestampMs

            if (
                elapsed < minFrameIntervalMs ||
                !isProcessingFrame.compareAndSet(false, true)
            ) {
                drainAndCloseSingleImage(reader)
                return
            }

            rawImage = reader.acquireLatestImage()

            if (rawImage == null) {
                isProcessingFrame.set(false)
                return
            }

            lastProcessedTimestampMs = now

            val rotationDegrees = resolveDisplayRotationDegrees()
            val width = captureWidth
            val height = captureHeight

            handedOffToCaller = true

            onFrameCaptured(
                rawImage,
                closeOnce,
                rotationDegrees,
                width,
                height
            )

        } catch (_: Throwable) {
            closeOnce()
            isProcessingFrame.set(false)

        } finally {
            if (!handedOffToCaller) {
                closeOnce()
            }

            val shouldTeardownNow: Boolean
            val notifySystemStopNow: Boolean

            synchronized(lifecycleLock) {
                inFlightOperations--

                shouldTeardownNow =
                    activeSessionId == sessionId &&
                    isStopping &&
                    inFlightOperations == 0

                notifySystemStopNow =
                    shouldTeardownNow && projectionStoppedBySystem

                if (notifySystemStopNow) {
                    projectionStoppedBySystem = false
                }
            }

            if (shouldTeardownNow) {
                performTeardownIfIdle(
                    expectedSessionId = sessionId,
                    notifySystemStop = notifySystemStopNow
                )
            }
        }
    }

    private fun drainAndCloseSingleImage(reader: ImageReader) {
        var image: Image? = null

        try {
            image = reader.acquireLatestImage()
        } catch (_: Exception) {
        } finally {
            if (image != null) {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCaptureMetrics(): CaptureMetrics {
        val displayMetrics = appContext.resources.displayMetrics
        val (rawWidth, rawHeight) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                bounds.width().coerceAtLeast(360) to
                    bounds.height().coerceAtLeast(640)
            } else {
                displayMetrics.widthPixels.coerceAtLeast(360) to
                    displayMetrics.heightPixels.coerceAtLeast(640)
            }

        return scaleCaptureMetrics(
            rawWidth,
            rawHeight,
            displayMetrics.densityDpi.coerceAtLeast(160)
        )
    }

    private fun scaleCaptureMetrics(
        rawWidth: Int,
        rawHeight: Int,
        baseDensityDpi: Int
    ): CaptureMetrics {
        val largestDimension = maxOf(rawWidth, rawHeight).coerceAtLeast(1)
        val scale = if (largestDimension > 1280) {
            1280f / largestDimension.toFloat()
        } else {
            1f
        }

        return CaptureMetrics(
            width = (rawWidth * scale).toInt().coerceAtLeast(1),
            height = (rawHeight * scale).toInt().coerceAtLeast(1),
            densityDpi = (baseDensityDpi * scale).toInt().coerceAtLeast(120)
        )
    }

    /**
     * Android 14+ can change the captured region from the full display to a single app
     * window, or resize it after rotation. Resize both the virtual display and its
     * ImageReader surface so OCR receives the actual app dimensions without letterboxing.
     */
    private fun handleCapturedContentResize(
        sessionId: Long,
        rawWidth: Int,
        rawHeight: Int
    ) {
        if (rawWidth <= 0 || rawHeight <= 0) return

        val baseDensity = appContext.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        val metrics = scaleCaptureMetrics(rawWidth, rawHeight, baseDensity)

        var oldReader: ImageReader? = null

        synchronized(lifecycleLock) {
            if (activeSessionId != sessionId || isStopping || projectionStoppedBySystem) {
                return
            }

            val currentDisplay = virtualDisplay
            if (currentDisplay == null) {
                pendingCapturedContentSize = rawWidth to rawHeight
                return
            }

            if (captureWidth == metrics.width && captureHeight == metrics.height) {
                return
            }

            val handler = handlerThread?.looper?.let { Handler(it) } ?: return
            val newReader = try {
                ImageReader.newInstance(
                    metrics.width,
                    metrics.height,
                    PixelFormat.RGBA_8888,
                    2
                )
            } catch (error: Exception) {
                android.util.Log.e("ScreenCaptureSession", "Could not resize capture reader", error)
                return
            }

            newReader.setOnImageAvailableListener({ readerRef ->
                handleImageAvailable(sessionId, readerRef)
            }, handler)

            try {
                currentDisplay.resize(metrics.width, metrics.height, metrics.densityDpi)
                currentDisplay.setSurface(newReader.surface)
            } catch (error: Exception) {
                newReader.setOnImageAvailableListener(null, null)
                newReader.close()
                android.util.Log.e("ScreenCaptureSession", "Could not resize virtual display", error)
                return
            }

            oldReader = imageReader
            imageReader = newReader
            captureWidth = metrics.width
            captureHeight = metrics.height
        }

        oldReader?.let { previous ->
            try {
                previous.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }
            try {
                previous.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveDisplayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val rotation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appContext.display?.rotation ?: windowManager.defaultDisplay.rotation
            } else {
                windowManager.defaultDisplay.rotation
            }

        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun performTeardownIfIdle(
        expectedSessionId: Long,
        notifySystemStop: Boolean
    ) {
        val displayToRelease: VirtualDisplay?
        val readerToRelease: ImageReader?
        val projectionToRelease: MediaProjection?
        val callbackToUnregister: MediaProjection.Callback?
        val threadToQuit: HandlerThread?
        val shouldNotify: Boolean

        synchronized(lifecycleLock) {
            if (
                activeSessionId != expectedSessionId ||
                !isStopping ||
                inFlightOperations > 0
            ) {
                return
            }

            displayToRelease = virtualDisplay
            readerToRelease = imageReader
            projectionToRelease = mediaProjection
            callbackToUnregister = activeProjectionCallback
            threadToQuit = handlerThread

            clearSessionFieldsLocked()

            isStopping = false
            isStarting = false
            isSessionActive = false
            isProcessingFrame.set(false)

            shouldNotify = notifySystemStop || projectionStoppedBySystem
            projectionStoppedBySystem = false
        }

        safeReleaseComponents(
            display = displayToRelease,
            reader = readerToRelease,
            projection = projectionToRelease,
            callback = callbackToUnregister,
            thread = threadToQuit,
            unregisterCallback =
                projectionToRelease != null && callbackToUnregister != null
        )

        if (shouldNotify) {
            notifyProjectionStoppedOnMainThread()
        }
    }

    private fun notifyProjectionStoppedOnMainThread() {
        mainHandler.post {
            try {
                onProjectionStopped()
            } catch (_: Throwable) {
            }
        }
    }

    private fun clearSessionFieldsLocked() {
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        activeProjectionCallback = null
        handlerThread = null
        pendingCapturedContentSize = null
    }

    private fun safeReleaseComponents(
        display: VirtualDisplay?,
        reader: ImageReader?,
        projection: MediaProjection?,
        callback: MediaProjection.Callback?,
        thread: HandlerThread?,
        unregisterCallback: Boolean
    ) {
        if (display != null) {
            try {
                display.release()
            } catch (_: Exception) {
            }
        }

        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null)
            } catch (_: Exception) {
            }

            try {
                reader.close()
            } catch (_: Exception) {
            }
        }

        if (projection != null) {
            if (unregisterCallback && callback != null) {
                try {
                    projection.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }

            try {
                projection.stop()
            } catch (_: Exception) {
            }
        }

        if (thread != null) {
            try {
                thread.quitSafely()
            } catch (_: Exception) {
            }
        }
    }

    private data class CaptureMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )
}
