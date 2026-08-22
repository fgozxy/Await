package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 一条倒数日日程。
 *
 * @param dateEpochDay 目标日期（本地时区，epoch day）
 * @param remindDaysBefore 提前提醒的天数列表；0 表示当天提醒，如 [0, 1, 3, 7]
 * @param remindHour / remindMinute 每次提醒的触发时刻
 * @param repeatYearly 是否按年重复（生日、纪念日等）
 */
data class Event(
    val id: Long = System.currentTimeMillis(),
    val title: String = "",
    val dateEpochDay: Long = LocalDate.now().toEpochDay(),
    val note: String = "",
    val pinned: Boolean = false,
    val colorIndex: Int = 0,
    val remindDaysBefore: List<Int> = listOf(1),
    val remindHour: Int = 9,
    val remindMinute: Int = 0,
    val repeatYearly: Boolean = false
) {
    /** 目标日期 */
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    /** 距目标日还有几天；负数表示已过去 N 天 */
    fun daysFromToday(): Int = ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()

    /** 下一个（或当前）周年日期。非重复事件返回原日期 */
    fun nextOccurrence(): LocalDate {
        if (!repeatYearly) return date
        var d = date
        val today = LocalDate.now()
        while (ChronoUnit.DAYS.between(today, d) < 0) d = d.plusYears(1)
        return d
    }

    /** 展示用日期文本，如 2026-10-01 */
    fun dateText(): String = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
}

/**
 * 基于 Gson + SharedPreferences 的轻量持久化。
 * 倒数日数据量小（通常几十条），无需引入数据库。
 */
object EventStore {
    private const val PREFS = "await_events"
    private const val KEY = "events_json"
    private val gson = Gson()

    fun load(context: Context): MutableList<Event> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val type = object : TypeToken<MutableList<Event>>() {}.type
            gson.fromJson<MutableList<Event>>(json, type)
        }.getOrNull() ?: mutableListOf()
    }

    fun save(context: Context, events: List<Event>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(events)).apply()
    }
}
