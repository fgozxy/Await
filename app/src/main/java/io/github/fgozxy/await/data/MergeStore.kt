package io.github.fgozxy.await.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一个合并通知组：同一天的若干条日程共用一次提醒 */
data class MergeGroup(
    val id: Long = System.currentTimeMillis(),
    val eventIds: List<Long> = emptyList()
)

/**
 * 合并通知的分组表。
 *
 * 解决的问题：同一天有好几件事时，到点会挨个响一遍。合并之后这一组只提醒一次，
 * 通知正文里把这一天的事一并列出来。
 *
 * 实现上刻意不改闹钟调度——每个成员的闹钟照旧各自存在，只在**触发那一刻**做去重：
 * 组内任一成员响过之后，[shouldAlert] 会让窗口期内的其他成员直接跳过。
 * 反过来做（只留一个成员的闹钟、取消其余）看着更"干净"，但一旦那个成员被删掉
 * 或改了日期，整组就会集体失声——漏提醒的代价远大于多写几行去重逻辑。
 */
object MergeStore {

    private const val PREFS = "await_merge"
    private const val KEY_GROUPS = "groups"
    private const val KEY_FIRED = "fired_at"

    /** 同组去重窗口：组内成员在这段时间内重复触发只算一次 */
    const val ALERT_WINDOW_MS = 2 * 60 * 1000L

    private val gson = Gson()

    fun load(context: Context): List<MergeGroup> {
        val json = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        val list = runCatching {
            val type = object : TypeToken<List<MergeGroup>>() {}.type
            gson.fromJson<List<MergeGroup>>(json, type)
        }.getOrNull() ?: return emptyList()
        return list.filterNotNull()
            .map { g -> g.copy(eventIds = g.eventIds.filterNotNull().distinct()) }
            .filter { it.eventIds.size >= 2 }
    }

    fun save(context: Context, groups: List<MergeGroup>) {
        prefs(context).edit()
            .putString(KEY_GROUPS, gson.toJson(groups.filter { it.eventIds.size >= 2 }))
            .apply()
    }

    /** 某条日程所属的合并组；没有则 null */
    fun groupOf(context: Context, eventId: Long): MergeGroup? =
        load(context).firstOrNull { eventId in it.eventIds }

    /**
     * 把若干条日程合并成一组。
     *
     * 这些 id 会先从原有的组里摘出来——一条日程只可能属于一个合并组，
     * 否则触发时到底按哪一组去重就说不清了。
     *
     * @return 新建的组；少于 2 条时不成组，返回 null
     */
    fun merge(context: Context, eventIds: Set<Long>): MergeGroup? {
        if (eventIds.size < 2) return null
        val rest = load(context)
            .map { g -> g.copy(eventIds = g.eventIds.filterNot { it in eventIds }) }
            .filter { it.eventIds.size >= 2 }
        val group = MergeGroup(eventIds = eventIds.sorted())
        save(context, rest + group)
        return group
    }

    /** 解散一个合并组 */
    fun unmerge(context: Context, groupId: Long) {
        save(context, load(context).filterNot { it.id == groupId })
        clearFired(context, groupId)
    }

    /**
     * 清理失效数据：日程被删掉后，成员随之减少；不足 2 条的组自动解散。
     * 每次读日程列表时顺手调用，避免存下越来越多的死组。
     */
    fun prune(context: Context, existingEventIds: Set<Long>) {
        val current = load(context)
        val pruned = current
            .map { g -> g.copy(eventIds = g.eventIds.filter { it in existingEventIds }) }
            .filter { it.eventIds.size >= 2 }
        if (pruned != current) save(context, pruned)
    }

    /**
     * 该组现在是否应该真正提醒（响铃 / 发通知）。
     *
     * true 表示这是本轮的第一个成员，同时把时间戳记下；窗口期内后续成员拿到 false，
     * 直接安静跳过。窗口取 2 分钟：同一时刻的闹钟实际触发可能差几秒到几十秒，
     * 而两次真正该提醒的时间点不会挨得这么近。
     */
    fun shouldAlert(context: Context, groupId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val sp = prefs(context)
        val key = "$KEY_FIRED:$groupId"
        val last = sp.getLong(key, 0L)
        // 时钟被往回调过（last 在未来）也当作该提醒了，否则会一直静音
        if (last in 1..now && now - last < ALERT_WINDOW_MS) return false
        sp.edit().putLong(key, now).apply()
        return true
    }

    private fun clearFired(context: Context, groupId: Long) {
        prefs(context).edit().remove("$KEY_FIRED:$groupId").apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
