
package com.ridecalculator.app.ocr

import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OcrTextRecognizer(
    private val onTextRecognized: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "RideCalculatorOCR"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    private val isClosed = AtomicBoolean(false)

    fun processImage(
        image: Image,
        closeImageOnce: () -> Unit,
        rotationDegrees: Int = 0,
        onFinished: () -> Unit
    ) {
        val finished = AtomicBoolean(false)
        val imageClosed = AtomicBoolean(false)
        val bitmapRecycled = AtomicBoolean(false)

        var bitmap: Bitmap? = null

        fun finishSafely() {
            if (finished.compareAndSet(false, true)) {
                try {
                    onFinished()
                } catch (e: Exception) {
                    Log.e(TAG, "Completion callback failed", e)
                }
            }
        }

        fun closeImageSafely() {
            if (imageClosed.compareAndSet(false, true)) {
                try {
                    closeImageOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Image close failed", e)
                }
            }
        }

        fun recycleBitmapSafely() {
            val current = bitmap
            if (current != null &&
                bitmapRecycled.compareAndSet(false, true)
            ) {
                try {
                    if (!current.isRecycled) {
                        current.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap recycle failed", e)
                }
            }
        }

        try {
            if (isClosed.get()) {
                closeImageSafely()
                finishSafely()
                return
            }

            val plane = image.planes.firstOrNull()
                ?: throw IllegalArgumentException("Image plane missing")

            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            require(width > 0 && height > 0) {
                "Invalid image dimensions"
            }

            require(pixelStride > 0) {
                "Invalid pixel stride"
            }

            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val sourceBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
            )

            sourceBitmap.copyPixelsFromBuffer(buffer)

            bitmap = Bitmap.createBitmap(
                sourceBitmap,
                0,
                0,
                width,
                height
            )

            if (sourceBitmap !== bitmap) {
                sourceBitmap.recycle()
            }

            // Image ab ScreenCaptureSession ke callback se close hogi.
            closeImageSafely()

            val currentBitmap = bitmap
                ?: throw IllegalStateException("Bitmap creation failed")

            // Capture is restricted to the selected inDrive app. Do not mask using
            // screen-coordinate overlay bounds: app captures can be resized/cropped,
            // which could black out the fare or distance bubbles by mistake.

            val safeRotation = if (rotationDegrees == 0 || rotationDegrees == 90 ||
                rotationDegrees == 180 || rotationDegrees == 270
            ) rotationDegrees else 0
            Log.d(TAG, "Processing frame " + currentBitmap.width + "x" +
                currentBitmap.height + ", rotation=" + safeRotation)
            val inputImage = InputImage.fromBitmap(
                currentBitmap,
                safeRotation
            )

            recognizer.process(inputImage)
                .addOnSuccessListener { result: Text ->
                    try {
                        if (!isClosed.get()) {
                            onTextRecognized(result.text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Text callback failed", e)
                        notifyErrorSafely("Text processing failed")
                    }
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "OCR recognition failed", error)
                    notifyErrorSafely("OCR recognition failed")
                }
                .addOnCompleteListener {
                    recycleBitmapSafely()
                    finishSafely()
                }

        } catch (e: Exception) {
            Log.e(TAG, "Image processing failed", e)

            closeImageSafely()
            recycleBitmapSafely()

            notifyErrorSafely("Image processing failed")
            finishSafely()
        }
    }

    private fun notifyErrorSafely(message: String) {
        if (isClosed.get()) return

        mainHandler.post {
            try {
                if (!isClosed.get()) {
                    onError(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error callback failed", e)
            }
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            mainHandler.post {
                try {
                    recognizer.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Recognizer close failed", e)
                }
            }
        }
    }
}
