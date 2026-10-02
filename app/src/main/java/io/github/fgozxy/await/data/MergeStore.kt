package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一个合并通知组：提醒设置完全一致的若干条日程共用一次提醒。 */
data class MergeGroup(
    val id: Long = System.currentTimeMillis(),
    val eventIds: List<Long> = emptyList()
)

/** 决定两条日程能否真正统一提醒的完整签名。 */
data class MergeKey(
    val occurrenceEpochDay: Long,
    val remindDaysBefore: List<Int>,
    val remindHour: Int,
    val remindMinute: Int
)

/** 合并通知的分组表。 */
object MergeStore {

    private const val PREFS = "await_merge"
    private const val KEY_GROUPS = "groups"
    private val gson = Gson()

    /** 日程当前的提醒签名；没有任何提醒时不能加入统一通知组。 */
    fun keyOf(event: Event): MergeKey? {
        val safe = event.sanitized()
        if (safe.remindDaysBefore.isEmpty()) return null
        return MergeKey(
            occurrenceEpochDay = safe.nextOccurrence().toEpochDay(),
            remindDaysBefore = safe.remindDaysBefore.distinct().sorted(),
            remindHour = safe.remindHour,
            remindMinute = safe.remindMinute
        )
    }

    /** 一组日程当前是否仍能在相同时间提醒。 */
    internal fun areCompatible(events: List<Event>): Boolean {
        if (events.size < 2) return false
        val keys = events.map { keyOf(it) }
        return keys.firstOrNull() != null && keys.distinct().size == 1
    }

    fun load(context: Context): List<MergeGroup> {
        val json = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        val parsed = runCatching {
            val type = object : TypeToken<List<MergeGroup?>>() {}.type
            gson.fromJson<List<MergeGroup?>>(json, type)
        }.getOrNull() ?: return emptyList()

        val basic = parsed.filterNotNull().mapNotNull { group ->
            @Suppress("UNCHECKED_CAST")
            val ids = (group.eventIds as? List<Long?>)
                ?.filterNotNull()
                ?.distinct()
                ?: emptyList()
            group.copy(eventIds = ids).takeIf { ids.size >= 2 }
        }
        val normalized = ensureUniqueIds(basic)
        if (normalized != basic) write(context, normalized)
        return normalized
    }

    fun save(context: Context, groups: List<MergeGroup>) {
        val valid = groups.map { it.copy(eventIds = it.eventIds.distinct()) }
            .filter { it.eventIds.size >= 2 }
        write(context, ensureUniqueIds(valid))
    }

    /** 返回某条日程当前有效的合并组；调用时会同步清理过期关系。 */
    fun groupOf(
        context: Context,
        eventId: Long,
        events: List<Event> = EventStore.load(context)
    ): MergeGroup? = prune(context, events).firstOrNull { eventId in it.eventIds }

    /** 把若干条提醒签名一致的日程合并成一组。 */
    fun merge(context: Context, eventIds: Set<Long>): MergeGroup? {
        if (eventIds.size < 2) return null
        val events = EventStore.load(context)
        val selected = events.filter { it.id in eventIds }
        if (selected.size != eventIds.size || !areCompatible(selected)) return null

        val current = prune(context, events)
        val rest = current
            .map { group -> group.copy(eventIds = group.eventIds.filterNot { it in eventIds }) }
            .filter { it.eventIds.size >= 2 }
        val group = MergeGroup(
            id = nextUniqueId(current.map { it.id }.toSet()),
            eventIds = eventIds.sorted()
        )
        save(context, rest + group)
        return group
    }

    /** 解散一个合并组。组 ID 已在读取时去重，所以只会影响目标组。 */
    fun unmerge(context: Context, groupId: Long) {
        save(context, load(context).filterNot { it.id == groupId })
    }

    /**
     * 清理并返回当前有效组。成员删除后可保留剩余成员；提醒签名不再一致则整组解散，
     * 避免通知把不同日期或不同提醒时刻的日程说成「同一天还有」。
     */
    fun prune(context: Context, events: List<Event>): List<MergeGroup> {
        val current = load(context)
        val valid = sanitizeGroups(current, events)
        if (valid != current) {
            save(context, valid)
        }
        return valid
    }

    /** 备份解析和恢复共用的纯数据校验。 */
    internal fun sanitizeGroups(groups: List<MergeGroup>, events: List<Event>): List<MergeGroup> {
        val byId = events.associateBy { it.id }
        val claimed = HashSet<Long>()
        val result = mutableListOf<MergeGroup>()
        groups.forEach { group ->
            val ids = group.eventIds.distinct().filter { it in byId && it !in claimed }
            val members = ids.mapNotNull(byId::get)
            if (ids.size >= 2 && areCompatible(members)) {
                claimed += ids
                result += group.copy(eventIds = ids.sorted())
            }
        }
        return ensureUniqueIds(result)
    }

    /** 恢复备份里的统一通知组。 */
    fun applyImport(
        context: Context,
        incoming: List<MergeGroup>?,
        replace: Boolean,
        events: List<Event>
    ) {
        val combined = when {
            replace -> incoming.orEmpty()
            incoming == null -> load(context)
            else -> {
                val incomingIds = incoming.flatMap { it.eventIds }.toSet()
                load(context).map { group ->
                    group.copy(eventIds = group.eventIds.filterNot { it in incomingIds })
                }.filter { it.eventIds.size >= 2 } + incoming
            }
        }
        save(context, sanitizeGroups(combined, events))
    }

    /** 在已有 ID 集合上生成唯一 ID，批量建组也不会撞同一毫秒。 */
    internal fun nextUniqueId(existing: Set<Long>, now: Long = System.currentTimeMillis()): Long {
        var candidate = now
        while (candidate in existing) {
            candidate = if (candidate == Long.MAX_VALUE) Long.MIN_VALUE else candidate + 1L
        }
        return candidate
    }

    /** 修复 v1.8.0 可能已经写入的重复组 ID。 */
    private fun ensureUniqueIds(groups: List<MergeGroup>): List<MergeGroup> {
        val used = HashSet<Long>()
        return groups.map { group ->
            if (used.add(group.id)) group
            else group.copy(id = nextUniqueId(used, group.id)).also { used += it.id }
        }
    }

    private fun write(context: Context, groups: List<MergeGroup>) {
        prefs(context).edit().putString(KEY_GROUPS, gson.toJson(groups)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
