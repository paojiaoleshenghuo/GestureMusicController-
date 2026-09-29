package com.example.gesturemusic.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.gesturemusic.R
import com.example.gesturemusic.gesture.GestureAction
import com.example.gesturemusic.gesture.HandGestureClassifier
import com.example.gesturemusic.gesture.MediaPipeHandHelper
import com.example.gesturemusic.gesture.NormalizedPoint
import com.example.gesturemusic.root.RootCommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 纯后台无 UI 手势常驻服务 (LifecycleService)
 * 专为高版本 Android 优化，无悬浮窗，纯后台分析帧。
 */
class GestureFloatingService : LifecycleService() {

    companion object {
        private const val TAG = "GestureBgService"
        private const val CHANNEL_ID = "GestureMusicChannel_V2"
        private const val NOTIFICATION_ID = 2002
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var mediaPipeHelper: MediaPipeHandHelper? = null
    private val gestureClassifier = HandGestureClassifier()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        isRunning = true
        
        startForegroundServiceNotification()
        
        initMediaPipe()
        startCamera()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台手势识别引擎",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手势隔空控制后台运行中")
            .setContentText("保持前置摄像头纯后台识别，无画面打扰")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initMediaPipe() {
        mediaPipeHelper = MediaPipeHandHelper(
            context = this,
            onResult = { landmarks: List<NormalizedPoint>, timestampMs: Long ->
                if (landmarks.isNotEmpty()) {
                    val action = gestureClassifier.processLandmarks(landmarks, timestampMs)
                    if (action != GestureAction.NONE) {
                        handleGestureAction(action)
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "MediaPipe Error: $err")
            }
        )
        mediaPipeHelper?.setupLandmarker()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 纯后台运算，移除 Preview，只保留 ImageAnalysis
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        mediaPipeHelper?.processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
                Log.i(TAG, "CameraX background bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGestureAction(action: GestureAction) {
        Log.i(TAG, "Action triggered: ${action.name}")
        serviceScope.launch {
            vibrateFeedback()
            when (action) {
                GestureAction.NEXT_TRACK -> {
                    RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_NEXT)
                }
                GestureAction.PREV_TRACK -> {
                    RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_PREVIOUS)
                }
                GestureAction.PLAY_PAUSE -> {
                    RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                GestureAction.NONE -> {}
            }
        }
    }

    private fun vibrateFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        isRunning = false
        cameraExecutor.shutdown()
        mediaPipeHelper?.close()
        serviceScope.cancel()
    }
}
