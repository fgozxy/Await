package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.github.fgozxy.await.notify.AlarmScheduler
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
 * 读取时同时兼容「裸数组」格式（即直接是 events 数组），方便手工编辑的文件也能导入。
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
        val events: List<Event> = emptyList()
    )

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
                events = events
            )
        )
    }

    /** 默认文件名：Await-backup-20260822-103000.json */
    fun defaultFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "Await-backup-${now.format(fileStampFormatter)}.json"

    /** 解析备份文本；失败时返回带中文原因的 Result */
    fun parse(json: String): Result<List<Event>> = runCatching {
        if (json.isBlank()) error("文件内容为空")
        val root = runCatching { JsonParser.parseString(json) }
            .getOrElse { error("不是合法的 JSON 文件") }
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("events") ->
                root.asJsonObject.getAsJsonArray("events")
            else -> error("这不是 Await 的备份文件")
        }
        val type = object : TypeToken<List<Event>>() {}.type
        val events: List<Event> = gson.fromJson(array, type) ?: emptyList()
        val valid = events.filterNotNull().filter { it.title.isNotBlank() }
        if (valid.isEmpty()) error("备份中没有可用的日程")
        // id 缺失/重复的数据补一个唯一 id，避免互相覆盖
        val seen = HashSet<Long>()
        valid.mapIndexed { i, e ->
            if (e.id <= 0L || !seen.add(e.id)) e.copy(id = System.currentTimeMillis() + i) else e
        }
    }

    /** 把解析出的日程写回本地，并重排全部闹钟 */
    fun applyImport(context: Context, incoming: List<Event>, mode: Mode): ImportResult {
        val current = EventStore.load(context)
        // 旧闹钟一律先取消，避免被删掉/被覆盖的日程留下孤儿闹钟
        current.forEach { AlarmScheduler.cancel(context, it.id) }

        val result: List<Event>
        var added = 0
        var updated = 0
        if (mode == Mode.REPLACE) {
            result = incoming
            added = incoming.size
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
        AlarmScheduler.scheduleAll(context)
        return ImportResult(added, updated, result.size)
    }
}
