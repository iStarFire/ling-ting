package com.tingyiting.ui.player

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化睡眠定时偏好：全局默认 + 每本专辑单独覆盖。
 * 切换专辑时优先取该专辑单独值，避免不同专辑共用一份设置。
 */
@Singleton
class SleepTimerPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sleep_timer_prefs", Context.MODE_PRIVATE)

    fun saveGlobal(choice: TimerChoice) {
        write(KEY_GLOBAL, choice)
    }

    fun getGlobal(): TimerChoice? = read(KEY_GLOBAL)

    fun saveForBook(bookId: Long, choice: TimerChoice) {
        write(bookKey(bookId), choice)
    }

    fun getForBook(bookId: Long): TimerChoice? = read(bookKey(bookId))

    /** 优先该专辑的单独设置，否则回退到全局默认。 */
    fun resolveForBook(bookId: Long): TimerChoice? =
        getForBook(bookId) ?: getGlobal()

    private fun bookKey(bookId: Long): String = "book_$bookId"

    private fun write(key: String, choice: TimerChoice) {
        val type = if (choice is TimerChoice.Minutes) TYPE_MINUTES else TYPE_EPISODES
        val value = when (choice) {
            is TimerChoice.Minutes -> choice.minutes
            is TimerChoice.Episodes -> choice.count
        }
        prefs.edit()
            .putString(key + "_type", type)
            .putInt(key + "_value", value)
            .apply()
    }

    private fun read(key: String): TimerChoice? {
        val type = prefs.getString(key + "_type", null) ?: return null
        val value = prefs.getInt(key + "_value", 0)
        if (value <= 0) return null
        return when (type) {
            TYPE_MINUTES -> TimerChoice.Minutes(value)
            TYPE_EPISODES -> TimerChoice.Episodes(value)
            else -> null
        }
    }

    private companion object {
        const val KEY_GLOBAL = "global"
        const val TYPE_MINUTES = "minutes"
        const val TYPE_EPISODES = "episodes"
    }
}