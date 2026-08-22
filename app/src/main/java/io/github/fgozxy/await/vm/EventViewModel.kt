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
        AlarmScheduler.cancel(ctx, eventId)
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

    private fun refresh() {
        _events.value = EventStore.load(getApplication()).sortedWithDefault()
    }

    private fun List<Event>.sortedWithDefault() =
        sortedWith(
            compareByDescending<Event> { it.pinned }
                .thenBy { it.daysFromToday().let { d -> if (d < 0) Int.MAX_VALUE + d else d } }
        )
}
