package com.example.gesturemusic.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 封装 MediaPipe HandLandmarker 实时处理类
 * 
 * 1. 纯本地离线推断 (支持 GPU / CPU 加速)。
 * 2. 自动检查 assets 或本地缓存中的 hand_landmarker.task 模型文件。
 * 3. 若无本地模型，支持一键从 Google 官方静态 CDN 自动拉取并持久化保存。
 */
class MediaPipeHandHelper(
    private val context: Context,
    private val onResult: (List<NormalizedPoint>, Long) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "MediaPipeHandHelper"
        const val MODEL_FILENAME = "hand_landmarker.task"
        const val MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    }

    private var handLandmarker: HandLandmarker? = null
    private var isInitializing = false

    /**
     * 初始化 MediaPipe HandLandmarker
     */
    fun setupLandmarker() {
        if (handLandmarker != null || isInitializing) return
        isInitializing = true

        try {
            val modelFile = getModelFile()
            val baseOptionsBuilder = BaseOptions.builder()

            if (modelFile != null && modelFile.exists()) {
                baseOptionsBuilder.setModelAssetPath(modelFile.absolutePath)
            } else if (hasAssetModel()) {
                baseOptionsBuilder.setModelAssetPath(MODEL_FILENAME)
            } else {
                isInitializing = false
                onError("未找到模型文件 $MODEL_FILENAME，请点击下载模型")
                return
            }

            // 优先尝试 GPU 委托，若设备不支持则降级为 CPU
            try {
                baseOptionsBuilder.setDelegate(Delegate.GPU)
            } catch (e: Exception) {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
            }

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    val landmarksList = extractLandmarks(result)
                    onResult(landmarksList, SystemClock.uptimeMillis())
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                    onError("MediaPipe: ${error.message}")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "HandLandmarker initialized successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing HandLandmarker: ${e.message}", e)
            onError("初始化失败: ${e.message}")
        } finally {
            isInitializing = false
        }
    }

    /**
     * 从 MediaPipe 返回结果提取 21 个归一化关键点
     */
    private fun extractLandmarks(result: HandLandmarkerResult): List<NormalizedPoint> {
        val landmarks = result.landmarks()
        if (landmarks.isEmpty() || landmarks[0].isEmpty()) {
            return emptyList()
        }

        val firstHand = landmarks[0]
        val points = ArrayList<NormalizedPoint>(firstHand.size)
        for (landmark in firstHand) {
            points.add(NormalizedPoint(landmark.x(), landmark.y(), landmark.z()))
        }
        return points
    }

    private var lastProcessedFrameTime = 0L

    /**
     * 将 CameraX 的 ImageProxy 转为 MediaPipe MPImage 并异步推断
     */
    fun processImageProxy(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        val currentTime = SystemClock.uptimeMillis()
        
        // 限制帧率：大约 15 FPS (每 66 毫秒处理一帧)，大幅降低后台电量与计算资源消耗
        if (landmarker == null || (currentTime - lastProcessedFrameTime) < 66) {
            imageProxy.close()
            return
        }
        lastProcessedFrameTime = currentTime

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val frameTime = SystemClock.uptimeMillis()
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 转换 ImageProxy 为正确旋转角度的 Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        return if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    /**
     * 检查本地是否已存在模型
     */
    fun isModelReady(): Boolean {
        return hasAssetModel() || (getModelFile()?.exists() == true)
    }

    private fun hasAssetModel(): Boolean {
        return try {
            context.assets.open(MODEL_FILENAME).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getModelFile(): File? {
        val file = File(context.filesDir, MODEL_FILENAME)
        return if (file.exists()) file else null
    }

    /**
     * 在线下载 MediaPipe 官方手部模型到本地存储
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(context.filesDir, MODEL_FILENAME)
            val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")

            val url = URL(MODEL_DOWNLOAD_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.connect()

            val fileLength = connection.contentLength
            val input = connection.inputStream
            val output = FileOutputStream(tempFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) {
                    val progress = ((total * 100) / fileLength).toInt()
                    withContext(Dispatchers.Main) { onProgress(progress) }
                }
            }

            output.flush()
            output.close()
            input.close()

            if (tempFile.renameTo(targetFile)) {
                setupLandmarker()
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Download model error: ${e.message}")
            false
        }
    }

    fun close() {
        try {
            handLandmarker?.close()
            handLandmarker = null
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
    }
}
