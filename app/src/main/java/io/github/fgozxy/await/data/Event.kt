package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 循环周期：订阅制缴费、房租、会员到期等场景 */
enum class Cycle(val label: String) {
    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    YEARLY("每年"),
    /** 自定义：每隔 N 天循环（N 存在 Event.repeatEveryDays 中） */
    EVERY_N_DAYS("每隔N天");

    /** 从指定日期推进一个标准周期 */
    fun advance(d: LocalDate): LocalDate = when (this) {
        DAILY -> d.plusDays(1)
        WEEKLY -> d.plusWeeks(1)
        MONTHLY -> d.plusMonths(1)
        YEARLY -> d.plusYears(1)
        else -> d
    }
}

/**
 * 一条倒数日日程。
 *
 * @param dateEpochDay 目标日期（本地时区，epoch day）
 * @param remindDaysBefore 提前提醒的天数列表；0 表示当天提醒，如 [0, 1, 3, 7]
 * @param remindHour / remindMinute 每次提醒的触发时刻
 * @param repeatCycle 循环周期（订阅制可选 MONTHLY 等）
 * @param repeatYearly 旧版字段，仅为兼容老数据保留；读取时若为 true 视作 YEARLY
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
    val repeatCycle: Cycle = Cycle.NONE,
    val repeatEveryDays: Int = 0,
    val repeatYearly: Boolean = false,
    /** 自定义分组名（如「订阅」「生日」），空串表示未分组 */
    val groupName: String = ""
) {
    /** 实际生效的循环周期（兼容旧数据的归一化结果） */
    val cycle: Cycle
        get() = if (repeatCycle == Cycle.NONE && repeatYearly) Cycle.YEARLY else repeatCycle

    /** 自定义循环的天数（1~3650，仅 EVERY_N_DAYS 时使用） */
    val everyDays: Int get() = repeatEveryDays.coerceIn(1, 3650)

    /** 循环徽标文本；不重复返回 null */
    fun cycleLabel(): String? = when {
        cycle == Cycle.NONE -> null
        cycle == Cycle.EVERY_N_DAYS -> "↻每${everyDays}天"
        else -> "↻${cycle.label}"
    }

    /** 按当前循环配置，从指定日期推进到下一周期 */
    fun advanceDate(d: LocalDate): LocalDate = when (cycle) {
        Cycle.EVERY_N_DAYS -> d.plusDays(everyDays.toLong())
        else -> cycle.advance(d)
    }

    /** 目标日期 */
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    /**
     * 下一个（或当前）周期日期。
     * 不重复事件返回原日期；循环事件自动向前滚动到今天及以后，
     * 例如每月 5 号交费、今天是 20 号，则滚动到下月 5 号。
     */
    fun nextOccurrence(): LocalDate {
        val target = date
        if (cycle == Cycle.NONE) return target
        var d = target
        val today = LocalDate.now()
        // 上限保护：最多推进 3650 个周期（约 10 年）
        repeat(3650) {
            if (ChronoUnit.DAYS.between(today, d) >= 0) return d
            d = advanceDate(d)
        }
        return d
    }

    /** 距目标日还有几天；负数表示已过去 N 天（循环事件永远基于下一周期计算） */
    fun daysFromToday(): Int = ChronoUnit.DAYS.between(LocalDate.now(), nextOccurrence()).toInt()

    /** 展示用日期文本。循环事件显示下一周期的日期 */
    fun dateText(): String {
        val d = nextOccurrence()
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
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
        val list = runCatching {
            val type = object : TypeToken<MutableList<Event>>() {}.type
            gson.fromJson<MutableList<Event>>(json, type)
        }.getOrNull() ?: mutableListOf()
        // 清掉无法解析的坏数据，防止崩溃
        list.removeAll { it == null || it.title.isBlank() }
        return list
    }

    fun save(context: Context, events: List<Event>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(events)).apply()
    }
}
