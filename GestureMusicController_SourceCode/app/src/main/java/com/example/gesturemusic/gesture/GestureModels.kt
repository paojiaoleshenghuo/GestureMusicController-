package com.example.gesturemusic.gesture

/**
 * 归一化手部关键点 (取值范围 0.0 ~ 1.0)
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

/**
 * 手势动作定义
 */
enum class GestureAction(val description: String, val icon: String) {
    NONE("无动作", ""),
    NEXT_TRACK("下一曲", "⏭️"),
    PREV_TRACK("上一曲", "⏮️"),
    PLAY_PAUSE("暂停/播放", "⏯️")
}

/**
 * 单手静态姿势
 */
enum class HandPose {
    UNKNOWN,
    OPEN_PALM,   // 张开手掌
    FIST         // 握拳
}

/**
 * 历史轨迹点
 */
data class TimedPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long
)
