package com.example.gesturemusic.gesture

import android.os.SystemClock
import kotlin.math.hypot

class HandGestureClassifier {

    // 提高所有动作的触发阈值，降低灵敏度以换取极致的准确率
    private var swipeThresholdX: Float = 0.25f // 横向滑动需超过屏幕宽度的25% (原为12%)
    private var minSwipeSpeed: Float = 0.15f   // 速度要求不再那么高，但必须是明确的、较长距离的挥动
    private var minSwipeDurationMs: Long = 400L // 下一曲的基础挥动时间要求 (0.4s)
    private var prevTrackSwipeDurationMs: Long = 800L // 上一曲的挥动时间要求翻倍 (0.8s)
    private var cooldownPeriodMs: Long = 1500L // 触发后冷却调整为 1.5 秒

    var isMirrored: Boolean = true

    // 状态机记录
    private var lastActionTime: Long = 0L
    private val trajectory = ArrayDeque<TimedPoint>()

    // 握拳停留计时
    private var fistStartTime: Long = 0L
    private var isFistActive: Boolean = false

    companion object {
        const val WRIST = 0
        const val INDEX_MCP = 5
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_TIP = 12
        const val RING_MCP = 13
        const val RING_TIP = 16
        const val PINKY_MCP = 17
        const val PINKY_TIP = 20
    }

    fun processLandmarks(landmarks: List<NormalizedPoint>, timestampMs: Long = SystemClock.uptimeMillis()): GestureAction {
        if (landmarks.size < 21) {
            isFistActive = false
            return GestureAction.NONE
        }

        // 冷却期拦截
        if (timestampMs - lastActionTime < cooldownPeriodMs) {
            trajectory.clear()
            isFistActive = false
            return GestureAction.NONE
        }

        val currentPose = classifyStaticPose(landmarks)

        // 1. 判定握拳 (必须持续停留 1 秒钟以上)
        if (currentPose == HandPose.FIST) {
            if (!isFistActive) {
                isFistActive = true
                fistStartTime = timestampMs
            } else {
                val elapsedFistTime = timestampMs - fistStartTime
                if (elapsedFistTime >= 2000L) { // 暂停(握拳)的触发阈值提高一倍，需连续握拳 > 2秒
                    triggerAction(timestampMs)
                    return GestureAction.PLAY_PAUSE
                }
            }
            // 如果处于握拳积累阶段，不判定滑动
            return GestureAction.NONE
        } else {
            isFistActive = false
        }

        // 2. 判定左右滑动 (需要长距离、持续一定时间的清晰挥手)
        val palmCenter = computePalmCenter(landmarks)
        val currentPoint = TimedPoint(palmCenter.x, palmCenter.y, timestampMs)
        trajectory.addLast(currentPoint)

        // 保留最近 1000ms 的运动轨迹
        while (trajectory.isNotEmpty() && (timestampMs - trajectory.first().timestamp > 1000)) {
            trajectory.removeFirst()
        }

        if (trajectory.size >= 5) {
            val oldest = trajectory.first()
            val dt = timestampMs - oldest.timestamp

            var dx = currentPoint.x - oldest.x
            val dy = currentPoint.y - oldest.y

            // 镜像修正
            if (isMirrored) {
                dx = -dx 
            }

            // 根据左右方向决定时间阈值：上一曲 (dx < 0) 门槛翻倍
            val requiredDuration = if (dx < 0) prevTrackSwipeDurationMs else minSwipeDurationMs

            if (dt > requiredDuration) { 
                val speedX = kotlin.math.abs(dx) / (dt / 1000f)
                val isPredominantlyHorizontal = kotlin.math.abs(dx) > (kotlin.math.abs(dy) * 0.8f)

                if (isPredominantlyHorizontal && kotlin.math.abs(dx) >= swipeThresholdX && speedX >= minSwipeSpeed) {
                    triggerAction(timestampMs)
                    return if (dx > 0) GestureAction.NEXT_TRACK else GestureAction.PREV_TRACK
                }
            }
        }

        return GestureAction.NONE
    }

    private fun triggerAction(timestampMs: Long) {
        lastActionTime = timestampMs
        trajectory.clear()
        isFistActive = false
    }

    fun classifyStaticPose(landmarks: List<NormalizedPoint>): HandPose {
        val wrist = landmarks[WRIST]
        val middleMcp = landmarks[MIDDLE_MCP]
        
        val palmLength = distance(wrist, middleMcp)
        if (palmLength == 0f) return HandPose.UNKNOWN

        // 提升手指状态判定的严谨度
        fun isExtended(tip: NormalizedPoint): Boolean {
            return distance(tip, wrist) > (palmLength * 1.8f)
        }

        fun isCurled(tip: NormalizedPoint, mcp: NormalizedPoint): Boolean {
            return distance(tip, mcp) < (palmLength * 0.8f)
        }

        val indexExt = isExtended(landmarks[INDEX_TIP])
        val middleExt = isExtended(landmarks[MIDDLE_TIP])
        val ringExt = isExtended(landmarks[RING_TIP])
        val pinkyExt = isExtended(landmarks[PINKY_TIP])

        val indexCurl = isCurled(landmarks[INDEX_TIP], landmarks[INDEX_MCP])
        val middleCurl = isCurled(landmarks[MIDDLE_TIP], landmarks[MIDDLE_MCP])
        val ringCurl = isCurled(landmarks[RING_TIP], landmarks[RING_MCP])
        val pinkyCurl = isCurled(landmarks[PINKY_TIP], landmarks[PINKY_MCP])

        val extendedCount = (if (indexExt) 1 else 0) + (if (middleExt) 1 else 0) + (if (ringExt) 1 else 0) + (if (pinkyExt) 1 else 0)
        
        // 握拳必须 4 根手指全都严重弯曲，严防误判
        if (indexCurl && middleCurl && ringCurl && pinkyCurl && extendedCount == 0) {
            return HandPose.FIST
        }

        if (extendedCount >= 3) {
            return HandPose.OPEN_PALM
        }

        return HandPose.UNKNOWN
    }

    private fun computePalmCenter(landmarks: List<NormalizedPoint>): NormalizedPoint {
        val wrist = landmarks[WRIST]
        val middleMcp = landmarks[MIDDLE_MCP]
        return NormalizedPoint((wrist.x + middleMcp.x) / 2f, (wrist.y + middleMcp.y) / 2f)
    }

    private fun distance(p1: NormalizedPoint, p2: NormalizedPoint): Float {
        return hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble()).toFloat()
    }

    fun reset() {
        trajectory.clear()
        lastActionTime = 0L
        isFistActive = false
    }
}
