package io.github.fgozxy.await.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.EventStore
import io.github.fgozxy.await.notify.AlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 日程仓库视图模型：持有日程列表状态，写操作即时持久化并同步闹钟。
 */
class EventViewModel(app: Application) : AndroidViewModel(app) {

    private val _events = MutableStateFlow(EventStore.load(app).sortedWithDefault())
    val events: StateFlow<List<Event>> = _events

    /** 新增或更新（id 相同视为更新） */
    fun upsert(event: Event) {
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list.add(event)
        EventStore.save(ctx, list)
        AlarmScheduler.scheduleEvent(ctx, event)
        refresh()
    }

    fun delete(eventId: Long) {
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)
        list.removeAll { it.id == eventId }
        EventStore.save(ctx, list)
        cancelAlarms(eventId)
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
     * @param deleteEvents true = 连同分组下的日程一起删除，并取消它们的闹钟；
     *                     false = 仅把日程移出分组、日程本身保留
     *                     （此时空串是空操作——未分组的日程没有分组可移出）
     * @return 受影响的日程条数
     */
    fun deleteGroups(names: Set<String>, deleteEvents: Boolean): Int {
        if (names.isEmpty()) return 0
        val ctx = getApplication<Application>()
        val list = EventStore.load(ctx)

        if (deleteEvents) {
            val doomed = list.filter { it.group in names }
            if (doomed.isEmpty()) return 0
            list.removeAll(doomed.toSet())
            EventStore.save(ctx, list)
            doomed.forEach { cancelAlarms(it.id) }
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
        if (count > 0) {
            EventStore.save(ctx, list)
            refresh()
        }
        return count
    }

    /** 取消一条日程的全部闹钟：周期闹钟 + 可能挂着的「稍后提醒」 */
    private fun cancelAlarms(eventId: Long) {
        val ctx = getApplication<Application>()
        AlarmScheduler.cancel(ctx, eventId)
        AlarmScheduler.cancelSnooze(ctx, eventId)
    }

    /** 外部（导入 / 云端恢复）直接改动了存储后，用它把列表刷成最新 */
    fun reload() = refresh()

    private fun refresh() {
        _events.value = EventStore.load(getApplication()).sortedWithDefault()
    }

    private fun List<Event>.sortedWithDefault() =
        sortedWith(
            compareByDescending<Event> { it.pinned }
                .thenBy { it.daysFromToday().let { d -> if (d < 0) Int.MAX_VALUE + d else d } }
        )
}
