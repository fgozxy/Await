package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 循环周期单位。
 * 存储格式为 "NAME:N"（如 "MONTH:3" 表示每 3 个月）；N 仅对非 NONE 有意义。
 */
enum class Cycle(val label: String, val unit: String) {
    NONE("不重复", ""),
    DAY("按天循环", "天"),
    WEEK("按周循环", "周"),
    MONTH("按月循环", "月"),
    YEAR("按年循环", "年");
}

/** 解析后的循环配置：单位 + 间隔数 */
data class Repeat(val cycle: Cycle, val n: Int)

/**
 * 一条倒数日日程。
 *
 * 存储层说明：
 *  - repeatSpec 为新格式字符串 "CYCLE:N"（对 R8 混淆免疫）
 *  - repeatCycle / repeatEveryDays / repeatYearly 为旧版遗留字段，仅用于读取兼容，
 *    新保存的数据不再写入这三个字段
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
    val repeatSpec: String? = null,
    // ── 以下为旧版遗留字段，只读兼容 ──
    val repeatCycle: String? = null,
    val repeatEveryDays: Int = 0,
    val repeatYearly: Boolean = false,
    /** 自定义分组名（如「订阅」「生日」），null/空串表示未分组 */
    val groupName: String? = null
) {
    /**
     * 当前循环配置（每次访问即时解析，兼容三代数据格式）。
     *
     * 注意：这里刻意用计算属性而不是 `by lazy`——委托会生成一个 `repeat$delegate` 字段，
     * Gson 会把它一起序列化进存储，再读回来时因其声明类型是接口 `kotlin.Lazy` 而
     * 抛 JsonIOException，导致整份数据解析失败（v1.2.0 的「保存不上」就是这么来的）。
     * 解析本身只是拆一个短字符串，开销可以忽略。
     */
    val repeat: Repeat get() = parseRepeat(repeatSpec, repeatCycle, repeatEveryDays, repeatYearly)

    val cycle: Cycle get() = repeat.cycle
    val repeatN: Int get() = repeat.n

    /** 分组名的空安全版本；未分组返回空串 */
    val group: String get() = groupName ?: ""

    /** 目标日期 */
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    /**
     * 下一个（或当前）周期日期。
     * 不重复事件返回原日期；循环事件自动向前滚动到今天及以后。
     */
    fun nextOccurrence(): LocalDate {
        val target = date
        val r = repeat
        if (r.cycle == Cycle.NONE) return target
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

    /** 循环徽标文本；不重复返回 null。如「↻每月」「↻每2周」「↻每45天」「↻每4年」 */
    fun cycleLabel(): String? {
        val r = repeat
        return when {
            r.cycle == Cycle.NONE -> null
            r.n <= 1 -> "↻每${r.cycle.unit}"
            else -> "↻每${r.n}${r.cycle.unit}"
        }
    }

    /** 按当前循环配置，从指定日期推进到下一周期 */
    fun advanceDate(d: LocalDate): LocalDate {
        val r = repeat
        val n = r.n.toLong()
        return when (r.cycle) {
            Cycle.DAY -> d.plusDays(n)
            Cycle.WEEK -> d.plusWeeks(n)
            Cycle.MONTH -> d.plusMonths(n)
            Cycle.YEAR -> d.plusYears(n)
            Cycle.NONE -> d
        }
    }

    companion object {
        /** 解析存储的循环配置；任何异常数据一律安全回退为「不重复」 */
        fun parseRepeat(spec: String?, legacyCycle: String?, legacyDays: Int, legacyYearly: Boolean): Repeat {
            // 1) 新格式 "CYCLE:N"
            if (!spec.isNullOrBlank()) {
                runCatching {
                    val parts = spec.split(':')
                    val c = Cycle.valueOf(parts[0].trim())
                    if (c == Cycle.NONE) return Repeat(Cycle.NONE, 1)
                    val n = (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 3650)
                    return Repeat(c, n)
                }
            }
            // 2) 旧版枚举名字符串
            when (legacyCycle?.trim()) {
                "DAILY" -> return Repeat(Cycle.DAY, 1)
                "WEEKLY" -> return Repeat(Cycle.WEEK, 1)
                "MONTHLY" -> return Repeat(Cycle.MONTH, 1)
                "YEARLY" -> return Repeat(Cycle.YEAR, 1)
                "EVERY_N_DAYS" -> return Repeat(Cycle.DAY, legacyDays.coerceIn(1, 3650))
            }
            // 3) 更早的布尔字段
            if (legacyYearly) return Repeat(Cycle.YEAR, 1)
            return Repeat(Cycle.NONE, 1)
        }
    }
}

/**
 * 基于 Gson + SharedPreferences 的轻量持久化。
 * 倒数日数据量小（通常几十条），无需引入数据库。
 */
object EventStore {
    private const val PREFS = "await_events"
    private const val KEY = "events_json"
    /** 解析失败时留存的原始数据，避免被后续保存直接覆盖掉 */
    private const val KEY_SALVAGE = "events_json_unreadable"

    /**
     * 排除名字含 `$` 的字段：Kotlin 的委托属性（`by lazy` 等）会生成
     * `xxx$delegate` 合成字段，写进 JSON 后再读回来会让整份数据解析失败。
     */
    private val gson: Gson = GsonBuilder()
        .setExclusionStrategies(object : ExclusionStrategy {
            override fun shouldSkipField(f: FieldAttributes) = f.name.contains('$')
            override fun shouldSkipClass(clazz: Class<*>) = false
        })
        .create()

    fun load(context: Context): MutableList<Event> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sp.getString(KEY, null) ?: return mutableListOf()
        val list = runCatching {
            val type = object : TypeToken<MutableList<Event>>() {}.type
            gson.fromJson<MutableList<Event>>(json, type)
        }.getOrNull()
        if (list == null) {
            // 解析不了就先把原文留一份：下一次保存会覆盖 KEY，留档才有机会人工找回
            if (sp.getString(KEY_SALVAGE, null) == null) {
                sp.edit().putString(KEY_SALVAGE, json).apply()
            }
            return mutableListOf()
        }
        // 清掉无法解析的坏数据，防止崩溃
        list.removeAll { it == null || it.title.isNullOrBlank() }
        return list
    }

    fun save(context: Context, events: List<Event>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(events)).apply()
    }
}
