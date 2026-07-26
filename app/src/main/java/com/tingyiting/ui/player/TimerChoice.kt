package com.tingyiting.ui.player

/** 睡眠定时设置：按时间或按集数停止播放。 */
sealed interface TimerChoice {
    data class Minutes(val minutes: Int) : TimerChoice
    /** 播完 count 集后停止；count >= 1，1 表示「播完本集」。 */
    data class Episodes(val count: Int) : TimerChoice

    /** 给用户看的简短描述。 */
    fun describe(): String = when (this) {
        is Minutes -> "$minutes 分钟后停止"
        is Episodes -> if (count <= 1) "播完本集后停止" else "播完$count 集后停止"
    }
}