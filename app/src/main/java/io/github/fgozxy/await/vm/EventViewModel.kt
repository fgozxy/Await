package io.github.fgozxy.await.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.data.GroupStore
import io.github.fgozxy.await.data.MergeGroup
import io.github.fgozxy.await.data.MergeStore
import io.github.fgozxy.await.sync.SyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 日程仓库视图模型：持有日程列表状态，写操作即时持久化并同步服务器日程。
 */
class EventViewModel(app: Application) : AndroidViewModel(app) {

    private val _events = MutableStateFlow(EventStore.load(app).sortedWithDefault())
    val events: StateFlow<List<Event>> = _events

    /**
     * 全部分组名：显式建过的 + 从日程里推导出来的，按名称排序。
     *
     * 两个来源缺一不可——只看日程，新建的空分组会立刻消失；只看存储，
     * 导入进来的日程带的分组就认不出来。
     */
    private val _groups = MutableStateFlow(loadGroups())
    val groups: StateFlow<List<String>> = _groups

    /** 合并通知组：同一天的若干日程共用一次提醒 */
    private val _mergeGroups = MutableStateFlow(loadMergeGroups())
    val mergeGroups: StateFlow<List<MergeGroup>> = _mergeGroups

    /**
     * 新建一个空分组。
     *
     * @return false = 名字为空，或该分组已经存在（大小写不敏感）
     */
    fun createGroup(name: String): Boolean {
        val ctx = getApplication<Application>()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        // 已经有日程用着这个名字，也算已存在——否则会建出一个看起来重复的分组
        if (_groups.value.any { it.equals(trimmed, ignoreCase = true) }) return false
        if (!GroupStore.add(ctx, trimmed)) return false
        _groups.value = loadGroups()
        return true
    }

    /**
     * 把若干条日程一次性归到某个分组。
     *
     * @param group 目标分组名；空串表示移出分组（归为「未分组」）
     * @return 实际改动的条数（本来就在该分组里的不计）
     */
    fun assignGroup(eventIds: Set<Long>, group: String): Int {
        if (eventIds.isEmpty()) return 0
        val ctx = getApplication<Application>()
        val target = group.trim()
        val list = EventStore.load(ctx)
        var count = 0
        for (i in list.indices) {
            if (list[i].id in eventIds && list[i].group != target) {
                list[i] = list[i].copy(groupName = target)
                count++
            }
        }
        if (count > 0) {
            EventStore.save(ctx, list)
            // 分组内容变了不影响提醒时刻，只需刷新列表
            refresh()
        }
        return count
    }

    /**
     * 把若干条日程合并成一个通知组。少于 2 条不成组。
     *
     * @return 是否真的建了组
     */
    fun mergeNotifications(eventIds: Set<Long>): Boolean {
        val created = MergeStore.merge(getApplication(), eventIds) != null
        if (created) {
            _mergeGroups.value = loadMergeGroups()
            SyncCoordinator.changed(getApplication())
        }
        return created
    }

    /** 解散一个合并通知组 */
    fun unmergeNotifications(groupId: Long) {
        MergeStore.unmerge(getApplication(), groupId)
        SyncCoordinator.changed(getApplication())
        _mergeGroups.value = loadMergeGroups()
    }

    /** 新增或更新（id 相同视为更新） */
    fun upsert(event: Event) {
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list.add(event)
        EventStore.save(ctx, list)
        refresh()
    }

    fun delete(eventId: Long) {
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)
        list.removeAll { it.id == eventId }
        EventStore.save(ctx, list)
        refresh()
    }

    fun togglePin(eventId: Long) {
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)
        val i = list.indexOfFirst { it.id == eventId }
        if (i >= 0) {
            list[i] = list[i].copy(pinned = !list[i].pinned)
            EventStore.save(ctx, list)
            refresh()
        }
    }

    /**
     * 批量删除分组。
     *
     * @param names 要删除的分组名；空串 `""` 代表「未分组」这个默认分组
     * @param deleteEvents true = 连同分组下的日程一起删除，并同步删除服务器上的提醒；
     *                     false = 仅把日程移出分组、日程本身保留
     *                     （此时空串是空操作——未分组的日程没有分组可移出）
     * @return 受影响的日程条数
     */
    fun deleteGroups(names: Set<String>, deleteEvents: Boolean): Int {
        if (names.isEmpty()) return 0
        val ctx = getApplication<Application>()
        // 名字本身先删掉，否则空分组删完还留在列表里
        GroupStore.remove(ctx, names)
        val list = EventStore.load(ctx)

        if (deleteEvents) {
            val doomed = list.filter { it.group in names }
            if (doomed.isEmpty()) { refresh(); return 0 }
            list.removeAll(doomed.toSet())
            EventStore.save(ctx, list)
            refresh()
            return doomed.size
        }

        var count = 0
        for (i in list.indices) {
            // 未分组（空串）本来就没有分组可移出，跳过
            if (list[i].group.isNotBlank() && list[i].group in names) {
                list[i] = list[i].copy(groupName = "")
                count++
            }
        }
        if (count > 0) EventStore.save(ctx, list)
        refresh()
        return count
    }

    /** 外部（导入 / 云端恢复）直接改动了存储后，用它把列表刷成最新 */
    fun reload() = refresh()

    private fun refresh() {
        val list = EventStore.load(getApplication())
        // 日程被删掉后，合并组里会留下悬空的 id；顺手清一次，不足 2 条的组自动解散
        MergeStore.prune(getApplication(), list)
        _events.value = list.sortedWithDefault()
        _groups.value = loadGroups()
        _mergeGroups.value = loadMergeGroups()
    }

    private fun loadMergeGroups(): List<MergeGroup> {
        val context = getApplication<Application>()
        return MergeStore.prune(context, EventStore.load(context))
    }

    /** 显式建过的分组 + 日程里出现过的分组，去重排序 */
    private fun loadGroups(): List<String> {
        val ctx = getApplication<Application>()
        val fromEvents = EventStore.load(ctx).map { it.group }.filter { it.isNotBlank() }
        return (GroupStore.load(ctx) + fromEvents).distinct().sorted()
    }

    private fun List<Event>.sortedWithDefault() =
        sortedWith(
            compareByDescending<Event> { it.pinned }
                .thenBy { it.daysFromToday().let { d -> if (d < 0) Int.MAX_VALUE + d else d } }
        )
}
