package com.example.a1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class HandDetector(context: Context) {
    private var handLandmarker: HandLandmarker? = null
    private val applicationContext = context.applicationContext
    var isInitialized = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            println("🔄 Starting HandDetector initialization...")

            val modelFile = File(applicationContext.filesDir, "hand_landmarker.task")

            if (!modelFile.exists()) {
                println("📥 Model not found. Downloading...")
                downloadModel(modelFile)
            } else {
                println("✅ Model file exists (${modelFile.length() / 1024}KB)")
            }

            // Verify file size
            if (modelFile.length() < 100000) { // Less than 100KB is suspicious
                println("⚠️ Model file too small, re-downloading...")
                modelFile.delete()
                downloadModel(modelFile)
            }

            // Initialize MediaPipe with proper settings
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(applicationContext, options)
            isInitialized = true
            println("✅ HandLandmarker initialized successfully!")

        } catch (e: Exception) {
            isInitialized = false
            println("❌ HandLandmarker initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun downloadModel(modelFile: File) {
        try {
            val modelUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            if (totalBytes % 100000 == 0L) {
                                println("📥 Downloaded ${totalBytes / 1024}KB...")
                            }
                        }
                        println("✅ Model downloaded successfully! (${totalBytes / 1024}KB)")
                    }
                }
            } else {
                throw Exception("HTTP error: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            println("❌ Model download failed: ${e.message}")
            throw e
        }
    }

    fun detectHands(imageProxy: ImageProxy): HandLandmarks? {
        if (!isInitialized || handLandmarker == null) {
            return null
        }

        return try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                println("⚠️ Failed to convert ImageProxy to Bitmap")
                return null
            }

            // Rotate bitmap to correct orientation
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

            // Create MediaPipe image
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            // Detect hands
            val result = handLandmarker?.detect(mpImage)

            if (result != null && result.landmarks().isNotEmpty()) {
                println("✋ Detected ${result.landmarks().size} hand(s)")
                convertToHandLandmarks(result)
            } else {
                null
            }

        } catch (e: Exception) {
            println("❌ Hand detection error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> yuv420ToBitmap(image)
                ImageFormat.JPEG -> jpegToBitmap(imageProxy)
                else -> {
                    println("⚠️ Unsupported image format: ${imageProxy.format}")
                    null
                }
            }
        } catch (e: Exception) {
            println("❌ Bitmap conversion error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun yuv420ToBitmap(image: Image): Bitmap? {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y channel
        yBuffer.get(nv21, 0, ySize)

        // Copy V and U channels (swap for NV21)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun jpegToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun convertToHandLandmarks(result: HandLandmarkerResult): HandLandmarks {
        var leftHand: List<Point>? = null
        var rightHand: List<Point>? = null

        result.landmarks().forEachIndexed { index, landmarks ->
            val handPoints = landmarks.map { landmark ->
                Point(landmark.x(), landmark.y(), landmark.z())
            }

            // Get handedness
            val handedness = result.handednesses().getOrNull(index)?.firstOrNull()
            val label = handedness?.categoryName()?.lowercase() ?: ""

            println("👋 Hand $index: $label (confidence: ${handedness?.score()})")

            // Note: MediaPipe returns "Left" for the person's left hand (which appears on right in selfie camera)
            when {
                label.contains("left") -> leftHand = handPoints
                label.contains("right") -> rightHand = handPoints
                else -> {
                    // Fallback: first hand is right, second is left
                    if (index == 0) rightHand = handPoints
                    else leftHand = handPoints
                }
            }
        }

        return HandLandmarks(leftHand, rightHand)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        isInitialized = false
        println("🔴 HandDetector closed")
    }
}