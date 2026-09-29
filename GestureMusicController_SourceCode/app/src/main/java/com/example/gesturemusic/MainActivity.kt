package com.example.gesturemusic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gesturemusic.databinding.ActivityMainBinding
import com.example.gesturemusic.gesture.GestureAction
import com.example.gesturemusic.gesture.HandGestureClassifier
import com.example.gesturemusic.gesture.MediaPipeHandHelper
import com.example.gesturemusic.gesture.NormalizedPoint
import com.example.gesturemusic.root.RootCommandExecutor
import com.example.gesturemusic.service.GestureFloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var mediaPipeHelper: MediaPipeHandHelper? = null
    private val gestureClassifier = HandGestureClassifier()

    // 权限请求启动器
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            checkAndSetupApp()
        } else {
            Toast.makeText(this, "需要相机权限方能识别前置手势！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupButtons()
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            checkAndSetupApp()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkAndSetupApp() {
        // 1. 异步检查 Root 状态
        lifecycleScope.launch {
            binding.tvRootStatus.text = "正在申请并检测 Root 权限..."
            val hasRoot = RootCommandExecutor.checkRootAccess()
            if (hasRoot) {
                binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                binding.tvRootStatus.text = "ROOT 状态: 已授权 (通过 su 极速注入指令)"
            } else {
                binding.tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                binding.tvRootStatus.text = "ROOT 状态: 未获得授权 (将使用普通媒体键接口兼容)"
            }
        }

        // 2. 初始化 MediaPipe 与相机
        setupMediaPipe()
    }

    private fun setupMediaPipe() {
        mediaPipeHelper = MediaPipeHandHelper(
            context = this,
            onResult = { landmarks: List<NormalizedPoint>, timestampMs: Long ->
                runOnUiThread {
                    binding.gestureOverlayView.updateLandmarks(landmarks, mirrored = true)

                    if (landmarks.isEmpty()) {
                        binding.tvDetectionStatus.text = "手势状态: 未检测到手部"
                    } else {
                        val pose = gestureClassifier.classifyStaticPose(landmarks)
                        val poseStr = when (pose) {
                            com.example.gesturemusic.gesture.HandPose.OPEN_PALM -> "张开手掌 ✋"
                            com.example.gesturemusic.gesture.HandPose.FIST -> "握拳 ✊"
                            else -> "手部移动中..."
                        }
                        binding.tvDetectionStatus.text = "手势状态: $poseStr (21关键点追踪中)"

                        // 动态识别动作判定
                        val action = gestureClassifier.processLandmarks(landmarks, timestampMs)
                        if (action != GestureAction.NONE) {
                            onGestureRecognized(action)
                        }
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.tvDetectionStatus.text = "MediaPipe 提示: $error"
                    if (!mediaPipeHelper!!.isModelReady()) {
                        showDownloadModelDialog()
                    }
                }
            }
        )

        if (mediaPipeHelper?.isModelReady() == true) {
            mediaPipeHelper?.setupLandmarker()
            startCamera()
        } else {
            showDownloadModelDialog()
        }
    }

    /**
     * 首次未检测到模型时，弹出快速下载提示
     */
    private fun showDownloadModelDialog() {
        AlertDialog.Builder(this)
            .setTitle("首次加载手部识别模型")
            .setMessage("检测到本地尚未包含 MediaPipe HandLandmarker 模型文件（约 8.9MB）。\n点击确定将一键自动高速下载至手机本地，下载完成后即可离线永久使用。")
            .setPositiveButton("立即下载") { _, _ ->
                downloadModel()
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadModel() {
        binding.tvDetectionStatus.text = "正在下载离线模型文件 (8.9MB)... 0%"
        lifecycleScope.launch {
            val success = mediaPipeHelper?.downloadModel { progress ->
                binding.tvDetectionStatus.text = "正在下载离线模型文件: $progress%"
            } ?: false

            if (success) {
                Toast.makeText(this@MainActivity, "模型下载完成，已激活本地离线识别！", Toast.LENGTH_SHORT).show()
                binding.tvDetectionStatus.text = "模型已加载，正在启动前置镜头..."
                startCamera()
            } else {
                Toast.makeText(this@MainActivity, "下载失败，请检查网络或将 hand_landmarker.task 放入 assets 目录", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 响应识别到的手势指令
     */
    private fun onGestureRecognized(action: GestureAction) {
        // 1. 震动反馈
        vibrateFeedback()

        // 2. 界面醒目动画提示
        binding.tvGestureFeedback.text = "${action.icon} ${action.description}"
        binding.tvGestureFeedback.visibility = View.VISIBLE

        val fadeOut = AlphaAnimation(1.0f, 0.0f).apply {
            duration = 1000
            startOffset = 500
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    binding.tvGestureFeedback.visibility = View.GONE
                }
                override fun onAnimationRepeat(a: Animation?) {}
            })
        }
        binding.tvGestureFeedback.startAnimation(fadeOut)

        // 3. 执行 Root 级媒体控制
        lifecycleScope.launch {
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

    private fun setupButtons() {
        // 手动测试按钮
        binding.btnTestPrev.setOnClickListener {
            vibrateFeedback()
            lifecycleScope.launch {
                RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_PREVIOUS)
                Toast.makeText(this@MainActivity, "指令已发送: ⏮️ 上一曲", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestPlayPause.setOnClickListener {
            vibrateFeedback()
            lifecycleScope.launch {
                RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_PLAY_PAUSE)
                Toast.makeText(this@MainActivity, "指令已发送: ⏯️ 播放/暂停", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestNext.setOnClickListener {
            vibrateFeedback()
            lifecycleScope.launch {
                RootCommandExecutor.sendMediaKeyEvent(applicationContext, RootCommandExecutor.KEYCODE_MEDIA_NEXT)
                Toast.makeText(this@MainActivity, "指令已发送: ⏭️ 下一曲", Toast.LENGTH_SHORT).show()
            }
        }

        // 悬浮窗后台服务开关
        binding.btnToggleFloatingService.setOnClickListener {
            toggleFloatingService()
        }
    }

    private fun toggleFloatingService() {
        val serviceIntent = Intent(this, GestureFloatingService::class.java)
        if (GestureFloatingService.isRunning) {
            stopService(serviceIntent)
            binding.btnToggleFloatingService.text = "开启后台手势识别引擎 (无需悬浮窗)"
            Toast.makeText(this, "后台识别已停止", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.btnToggleFloatingService.text = "关闭后台手势识别引擎"
            Toast.makeText(this, "纯后台识别已开启！\n现在您可以退到桌面或熄屏测试手势", Toast.LENGTH_LONG).show()
        }
    }

    private fun vibrateFeedback() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaPipeHelper?.close()
        RootCommandExecutor.close()
    }
}
