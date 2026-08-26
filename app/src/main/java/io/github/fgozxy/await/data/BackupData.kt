package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.github.fgozxy.await.notify.AlarmScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 备份文件的读写：把全部日程序列化成一个自描述的 JSON，供本地导入导出与 WebDAV 备份共用。
 *
 * 文件格式（formatVersion = 1）：
 * ```json
 * {
 *   "app": "Await", "formatVersion": 1,
 *   "exportedAt": "2026-08-22 10:30:00", "appVersion": "1.3.0",
 *   "eventCount": 12,
 *   "events": [ { "id": 1690000000000, "title": "…", … } ]
 * }
 * ```
 * 读取时同时兼容「裸数组」格式（即直接是 events 数组），方便手工编辑的文件也能导入；
 * 日期也允许写成 `"date": "2026-10-01"`（见 [normalizeDates]），不必自己换算 epoch day。
 */
object BackupData {

    const val FORMAT_VERSION = 1

    /** Kotlin `by lazy` 会生成 `repeat$delegate` 字段，序列化出去毫无意义且会干扰导入，统一忽略 */
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .setExclusionStrategies(object : ExclusionStrategy {
            override fun shouldSkipField(f: FieldAttributes) = f.name.contains('$')
            override fun shouldSkipClass(clazz: Class<*>) = false
        })
        .create()

    private val stampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    data class Payload(
        val app: String = "Await",
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: String = "",
        val appVersion: String = "",
        val eventCount: Int = 0,
        val events: List<Event> = emptyList(),
        /** v1.7.2 起备份显式创建的分组；旧备份没有该字段，解析时以 null 区分 */
        val groups: List<String> = emptyList(),
        /** v1.8.1 起备份统一通知组 */
        val mergeGroups: List<MergeGroup> = emptyList()
    )

    /** null 表示旧备份或裸数组里没有对应元数据。 */
    data class ImportBundle(
        val events: List<Event>,
        val groups: List<String>? = null,
        val mergeGroups: List<MergeGroup>? = null
    )

    /**
     * 「把别的倒数日 App 截图交给 AI，让它吐出可导入的 JSON」用的现成提示词。
     *
     * 放在数据层而不是 UI 里：它描述的就是 [parse] 认得的那套字段，
     * 两者必须同步——以后改导入格式，这段也要跟着改。
     */
    const val AI_PROMPT = """你是数据迁移助手。我会给你一款倒数日 / 纪念日 App 的截图，请把其中所有日程提取成 JSON 数组。
只输出 JSON 本身，不要解释文字，不要 markdown 代码块。

格式示例：
[
  {
    "title": "房租",
    "date": "2026-10-01",
    "note": "备注，没有就省略",
    "groupName": "分组名，没有就省略",
    "repeatSpec": "MONTH:1",
    "remindDaysBefore": [1],
    "remindHour": 9,
    "remindMinute": 0
  }
]

规则：
1. title 必填，其余字段截图里没有就省略，不要编造。
2. date 一律写成 YYYY-MM-DD。
3. repeatSpec 取值 "DAY:N" "WEEK:N" "MONTH:N" "YEAR:N"，N 为间隔数；
   生日、纪念日用 "YEAR:1"；不重复的条目省略该字段。
4. remindDaysBefore 是提前几天提醒的数组，0 表示当天，可多选，如 [0, 1, 7]。
5. remindHour / remindMinute 是提醒时刻，省略则为 09:00。
6. groupName 是分组名，如「生日」「订阅」；截图里有分类就照抄。"""

    /** 导入方式 */
    enum class Mode(val label: String, val desc: String) {
        MERGE("合并", "保留现有日程，同一条日程以备份中的为准"),
        REPLACE("覆盖", "清空现有日程，只保留备份中的内容")
    }

    data class ImportResult(val added: Int, val updated: Int, val total: Int)

    /** 生成备份 JSON 文本 */
    fun exportJson(context: Context): String {
        val events = EventStore.load(context)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
        return gson.toJson(
            Payload(
                exportedAt = LocalDateTime.now().format(stampFormatter),
                appVersion = version,
                eventCount = events.size,
                events = events,
                groups = GroupStore.load(context),
                mergeGroups = MergeStore.prune(context, events)
            )
        )
    }

    /** 默认文件名：Await-backup-20260822-103000.json */
    fun defaultFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "Await-backup-${now.format(fileStampFormatter)}.json"

    /** 兼容旧调用：只取日程。需要恢复空分组时使用 [parseBundle]。 */
    fun parse(json: String): Result<List<Event>> = parseBundle(json).map { it.events }

    /** 解析备份文本；失败时返回带中文原因的 Result */
    fun parseBundle(json: String): Result<ImportBundle> = runCatching {
        if (json.isBlank()) error("内容为空")
        val body = extractJson(json)
        val root = runCatching { JsonParser.parseString(body) }
            .getOrElse { error("不是合法的 JSON") }
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("events") ->
                root.asJsonObject.getAsJsonArray("events")
            else -> error("这不是 Await 的备份文件")
        }
        normalizeDates(array)
        val type = object : TypeToken<List<Event?>>() {}.type
        val events: List<Event?> = gson.fromJson(array, type) ?: emptyList()
        val valid = events.filterNotNull().map { it.sanitized() }.filter { it.title.isNotBlank() }
        // 自描述备份允许 0 条日程：用户可能只备份了刚建好的空分组，或希望覆盖恢复为空。
        // 裸数组仍要求至少有一条有效日程，避免把误粘贴的 [] 当成一次有效导入。
        if (valid.isEmpty() && root.isJsonArray) error("备份中没有可用的日程")
        // id 缺失/重复的数据补一个唯一 id，避免互相覆盖
        val seen = HashSet<Long>()
        val normalizedEvents = valid.mapIndexed { i, e ->
            if (e.id <= 0L || !seen.add(e.id)) e.copy(id = System.currentTimeMillis() + i) else e
        }
        val groups = root.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.takeIf { it.has("groups") }
            ?.get("groups")
            ?.takeIf { it.isJsonArray }
            ?.let { groupElement ->
                val groupType = object : TypeToken<List<String>>() {}.type
                val raw: List<String?> = gson.fromJson(groupElement, groupType) ?: emptyList()
                raw.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
        val mergeGroups = root.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.takeIf { it.has("mergeGroups") }
            ?.get("mergeGroups")
            ?.takeIf { it.isJsonArray }
            ?.let { mergeElement ->
                val mergeType = object : TypeToken<List<MergeGroup?>>() {}.type
                val raw: List<MergeGroup?> = gson.fromJson(mergeElement, mergeType) ?: emptyList()
                MergeStore.sanitizeGroups(raw.filterNotNull(), normalizedEvents)
            }
        ImportBundle(normalizedEvents, groups, mergeGroups)
    }

    /**
     * 从可能夹带解释文字或 markdown 代码块的文本里抠出 JSON 主体。
     *
     * 直接粘贴 AI 回复是最省事的迁移路径，而模型十有八九不会老老实实只吐 JSON：
     * 要么裹一层 ```json 围栏，要么前后各加一句「好的，这是转换结果」。
     * 与其让用户回去手工删干净，不如在这里容忍掉。
     */
    private fun extractJson(raw: String): String {
        val text = raw.trim()
        // 1) markdown 代码围栏：取第一段围栏内的内容
        Regex("```(?:json)?\\s*([\\s\\S]*?)```")
            .find(text)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // 2) 已经是纯 JSON
        if (text.startsWith("[") || text.startsWith("{")) return text
        // 3) 前后夹带说明文字：截取第一个开括号到最后一个闭括号
        val start = text.indexOfFirst { it == '[' || it == '{' }
        val end = text.indexOfLast { it == ']' || it == '}' }
        return if (start in 0..<end) text.substring(start, end + 1) else text
    }

    /**
     * 把条目里的 `date` 字符串就地翻译成 [Event.dateEpochDay]。
     *
     * 存储层的日期是 epoch day（1970-01-01 起的天数），这个数字人算不出来、
     * 大模型也经常算错，而算错了不会报错——只会静默生成一条日期离谱的日程。
     * 所以导入时额外认一个人类可读的 `date` 字段，专供手工编辑和「截图交给 AI
     * 转 JSON」这类迁移场景。
     *
     * `date` 解析成功时优先于 `dateEpochDay`：应用自己导出的文件根本不含 `date`，
     * 两者同时出现只可能来自手写/生成的数据，那种情况下人写的日期才是本意，
     * 旁边那个 epoch day 恰恰是最可能算错的部分。解析失败则原样不动，
     * 让后面照旧走 `dateEpochDay` 或默认值，不因为一条格式古怪的日期毁掉整次导入。
     */
    private fun normalizeDates(array: JsonArray) {
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val raw = obj.get("date")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?: return@forEach
            val day = parseDateText(raw) ?: return@forEach
            obj.addProperty("dateEpochDay", day)
        }
    }

    /**
     * 宽松解析日期文本，返回 epoch day；认不出来返回 null。
     *
     * 刻意不用 [java.time.format.DateTimeFormatter]：手写和模型生成的日期
     * 分隔符五花八门（`-` `/` `.`），月日补不补零也随缘，还常常拖一个
     * `T00:00:00` 的尾巴。与其排列组合一堆 formatter，不如直接抓 年-月-日 三个数字。
     */
    private fun parseDateText(text: String): Long? {
        val m = DATE_PATTERN.find(text.trim()) ?: return null
        val (y, mo, d) = m.destructured
        return runCatching {
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                // 年份离谱的多半是解析错位（如把时长/编号当成日期），宁可丢弃
                .takeIf { it.year in 1900..2999 }
                ?.toEpochDay()
        }.getOrNull()
    }

    /** `2026-10-01` / `2026/10/1` / `2026.10.01` / `2026-10-01T09:00` 都能命中 */
    private val DATE_PATTERN = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")

    /** 把解析出的日程写回本地，并重排全部闹钟 */
    fun applyImport(
        context: Context,
        incoming: List<Event>,
        mode: Mode,
        incomingGroups: List<String>? = null,
        incomingMergeGroups: List<MergeGroup>? = null
    ): ImportResult {
        val current = EventStore.load(context)
        // 旧闹钟一律先取消，避免被删掉/被覆盖的日程留下孤儿闹钟
        current.forEach { AlarmScheduler.cancel(context, it.id) }

        val result: List<Event>
        var added = 0
        var updated = 0
        if (mode == Mode.REPLACE) {
            result = incoming.map { it.sanitized() }
            added = result.size
        } else {
            val merged = current.toMutableList()
            incoming.forEach { e ->
                val idx = merged.indexOfFirst { it.id == e.id }
                if (idx >= 0) {
                    merged[idx] = e; updated++
                } else {
                    merged.add(e); added++
                }
            }
            result = merged
        }
        EventStore.save(context, result)
        if (incomingGroups != null) {
            val safeGroups = incomingGroups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            GroupStore.save(
                context,
                if (mode == Mode.REPLACE) safeGroups
                else (GroupStore.load(context) + safeGroups).distinct()
            )
        }
        val stored = EventStore.load(context)
        MergeStore.applyImport(
            context = context,
            incoming = incomingMergeGroups,
            replace = mode == Mode.REPLACE,
            events = stored
        )
        AlarmScheduler.scheduleAll(context)
        return ImportResult(added, updated, result.size)
    }
}
