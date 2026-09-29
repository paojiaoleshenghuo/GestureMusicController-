package com.example.gesturemusic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.gesturemusic.gesture.NormalizedPoint

/**
 * 实时手部骨骼与手势轨迹绘制视图
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val landmarks = mutableListOf<NormalizedPoint>()
    private var isMirrored = true

    // 画笔
    private val pointPaint = Paint().apply {
        color = Color.parseColor("#00E676") // 荧光绿
        style = Paint.Style.FILL
        strokeWidth = 14f
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#8000E676") // 半透明绿
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val palmPaint = Paint().apply {
        color = Color.parseColor("#4000B0FF") // 掌心半透明蓝
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // MediaPipe 手部 21 关键点骨骼连线图
    private val connections = listOf(
        // 大拇指
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
        // 食指
        Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
        // 中指
        Pair(9, 10), Pair(10, 11), Pair(11, 12),
        // 无名指
        Pair(13, 14), Pair(14, 15), Pair(15, 16),
        // 小指
        Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
        // 掌心横向关节连线
        Pair(5, 9), Pair(9, 13), Pair(13, 17)
    )

    fun updateLandmarks(newLandmarks: List<NormalizedPoint>, mirrored: Boolean = true) {
        landmarks.clear()
        landmarks.addAll(newLandmarks)
        this.isMirrored = mirrored
        postInvalidate()
    }

    fun clear() {
        landmarks.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        fun mapX(normX: Float): Float {
            return if (isMirrored) {
                (1f - normX) * viewWidth
            } else {
                normX * viewWidth
            }
        }

        fun mapY(normY: Float): Float {
            return normY * viewHeight
        }

        // 1. 绘制骨骼连线
        for (connection in connections) {
            val startIdx = connection.first
            val endIdx = connection.second
            if (startIdx < landmarks.size && endIdx < landmarks.size) {
                val start = landmarks[startIdx]
                val end = landmarks[endIdx]

                val startX = mapX(start.x)
                val startY = mapY(start.y)
                val endX = mapX(end.x)
                val endY = mapY(end.y)

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }

        // 2. 绘制 21 个关节圆点
        for (i in landmarks.indices) {
            val point = landmarks[i]
            val x = mapX(point.x)
            val y = mapY(point.y)

            // 指尖（4, 8, 12, 16, 20）绘制更亮、稍大的点
            if (i in listOf(4, 8, 12, 16, 20)) {
                pointPaint.color = Color.parseColor("#FFD600") // 亮黄指尖
                canvas.drawCircle(x, y, 10f, pointPaint)
            } else {
                pointPaint.color = Color.parseColor("#00E676") // 绿色关节
                canvas.drawCircle(x, y, 7f, pointPaint)
            }
        }
    }
}
