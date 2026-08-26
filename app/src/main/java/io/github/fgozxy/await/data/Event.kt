package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.YearMonth
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
    val groupName: String? = null,
    /**
     * 闹钟式提醒：到点后持续响铃 + 震动，不手动关闭就一直响。
     *
     * 用可空布尔而不是 `Boolean = true`：老数据的 JSON 里没有这个字段，
     * 可空 + 计算属性是唯一与 Gson 的对象构造方式无关的写法（本项目已经
     * 因为字段反序列化丢过一次全量数据，见上方 `repeat` 的注释）。
     */
    val alarmMode: Boolean? = null
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

    /** 闹钟模式的空安全版本；老数据（null）一律视为开启 */
    val isAlarmMode: Boolean get() = alarmMode ?: true

    /** 目标日期 */
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

    /**
     * 下一个（或当前）周期日期。
     * 不重复事件返回原日期；循环事件自动向前滚动到今天及以后。
     */
    fun nextOccurrence(): LocalDate {
        return occurrenceOnOrAfter(LocalDate.now()) ?: date
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

    /**
     * 返回不早于 [threshold] 的第一个周期日期；不重复且原日期已过时返回 null。
     *
     * 每次都从原始日期按「第 N 个周期」计算，不能在上一次结果上继续 plusMonths/plusYears：
     * 1 月 31 日先被截成 2 月 28 日后，链式推进会永久变成每月 28 日；闰日也会永远丢失。
     * 直接估算周期序号同时去掉了旧实现 3650 次循环的上限。
     */
    fun occurrenceOnOrAfter(threshold: LocalDate): LocalDate? {
        val target = date
        val r = repeat
        if (r.cycle == Cycle.NONE) return target.takeIf { !it.isBefore(threshold) }
        if (!target.isBefore(threshold)) return target

        val units = when (r.cycle) {
            Cycle.DAY -> ChronoUnit.DAYS.between(target, threshold)
            Cycle.WEEK -> ChronoUnit.DAYS.between(target, threshold) / 7L
            Cycle.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(target), YearMonth.from(threshold))
            Cycle.YEAR -> (threshold.year - target.year).toLong()
            Cycle.NONE -> 0L
        }.coerceAtLeast(0L)

        var index = units / r.n.toLong()
        var candidate = occurrenceAt(index) ?: return null
        while (candidate.isBefore(threshold)) {
            index++
            candidate = occurrenceAt(index) ?: return null
        }
        return candidate
    }

    /** 始终以原始目标日期为锚点计算第 [index] 个周期，避免月末/闰日漂移。 */
    private fun occurrenceAt(index: Long): LocalDate? = runCatching {
        val amount = Math.multiplyExact(repeat.n.toLong(), index)
        when (repeat.cycle) {
            Cycle.DAY -> date.plusDays(amount)
            Cycle.WEEK -> date.plusWeeks(amount)
            Cycle.MONTH -> date.plusMonths(amount)
            Cycle.YEAR -> date.plusYears(amount)
            Cycle.NONE -> date
        }
    }.getOrNull()

    /**
     * 清洗来自 Gson/旧版本存储的数据。
     *
     * Gson 可以把 JSON null 塞进 Kotlin 的非空字段，也能绕过 UI 的数值范围限制；
     * 所有落库、导入和调度入口都调用这里，避免一条坏数据让应用以后每次启动都崩溃。
     */
    @Suppress("UNCHECKED_CAST", "USELESS_CAST")
    fun sanitized(): Event {
        val safeTitle = (title as String?).orEmpty().trim()
        val safeNote = (note as String?).orEmpty()
        val rawReminders = remindDaysBefore as? List<Int?>
        val safeReminders = rawReminders
            ?.filterNotNull()
            ?.filter { it in 0..MAX_REMIND_DAYS }
            ?.distinct()
            ?.sorted()
            ?: listOf(DEFAULT_REMIND_DAYS)
        val safeDate = runCatching { LocalDate.ofEpochDay(dateEpochDay) }
            .getOrNull()
            ?.takeIf { it.year in MIN_IMPORT_YEAR..MAX_IMPORT_YEAR }
            ?.toEpochDay()
            ?: LocalDate.now().toEpochDay()

        return copy(
            title = safeTitle,
            dateEpochDay = safeDate,
            note = safeNote,
            colorIndex = colorIndex.coerceAtLeast(0),
            remindDaysBefore = safeReminders,
            remindHour = remindHour.takeIf { it in 0..23 } ?: DEFAULT_REMIND_HOUR,
            remindMinute = remindMinute.takeIf { it in 0..59 } ?: DEFAULT_REMIND_MINUTE
        )
    }

    companion object {
        private const val DEFAULT_REMIND_DAYS = 1
        private const val DEFAULT_REMIND_HOUR = 9
        private const val DEFAULT_REMIND_MINUTE = 0
        private const val MAX_REMIND_DAYS = 3650
        private const val MIN_IMPORT_YEAR = 1900
        private const val MAX_IMPORT_YEAR = 2999

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
            val type = object : TypeToken<MutableList<Event?>>() {}.type
            gson.fromJson<MutableList<Event?>>(json, type)
        }.getOrNull()
        if (list == null) {
            // 解析不了就先把原文留一份：下一次保存会覆盖 KEY，留档才有机会人工找回
            if (sp.getString(KEY_SALVAGE, null) == null) {
                sp.edit().putString(KEY_SALVAGE, json).apply()
            }
            return mutableListOf()
        }
        // 清掉无法解析的坏数据并修正越界/null 字段，防止启动后在 UI 或闹钟调度中崩溃
        return list.mapNotNull { raw ->
            raw?.sanitized()?.takeIf { it.title.isNotBlank() }
        }.toMutableList()
    }

    fun save(context: Context, events: List<Event>) {
        val safe = events.map { it.sanitized() }.filter { it.title.isNotBlank() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(safe)).apply()
    }
}
