package io.github.fgozxy.await.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.MergeKey
import io.github.fgozxy.await.data.MergeStore
import io.github.fgozxy.await.vm.EventViewModel
import java.time.LocalDate

/**
 * 「统一通知」整页对话框。
 *
 * 自动把日程按「下一次发生日期 + 提醒设置」归拢，只有实际会同时触发且提醒模式一致的
 * 日程才允许合并。勾选后建成合并组：到点只提醒一次，通知正文里带上其他成员。
 *
 * 合并只影响提醒的发出方式，不动日程本身，也不动闹钟调度：每个成员的闹钟照旧存在，
 * 去重发生在触发那一刻（见 MergeStore.shouldAlert）。所以解散合并组是完全无损的，
 * 某个成员被删掉也不会连累同组其他日程漏提醒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedNotifyScreen(viewModel: EventViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val events by viewModel.events.collectAsStateWithLifecycle()
    val mergeGroups by viewModel.mergeGroups.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf(setOf<Long>()) }

    // 只有目标日、提前天数、时刻、提醒模式全部相同，闹钟才会真正同时触发。
    val compatibleBuckets: List<Pair<MergeKey, List<Event>>> = remember(events) {
        events.mapNotNull { event -> MergeStore.keyOf(event)?.let { it to event } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .filter { it.value.size >= 2 }
            .toList()
            .sortedWith(compareBy({ it.first.occurrenceEpochDay }, { it.first.remindHour }, { it.first.remindMinute }))
    }
    val mergedIds = remember(mergeGroups) { mergeGroups.flatMap { it.eventIds }.toSet() }
    val allCandidateIds = remember(compatibleBuckets) {
        compatibleBuckets.flatMap { it.second }.map { it.id }.toSet()
    }

    fun toggleAll(on: Boolean) {
        selected = if (on) allCandidateIds else emptySet()
    }

    /** 每种兼容的提醒签名各自成组。 */
    fun mergeSelected() {
        var groups = 0
        var count = 0
        compatibleBuckets.forEach { (_, dayEvents) ->
            val ids = dayEvents.map { it.id }.filter { it in selected }.toSet()
            if (ids.size >= 2 && viewModel.mergeNotifications(ids)) {
                groups++
                count += ids.size
            }
        }
        selected = emptySet()
        Toast.makeText(
            context,
            if (groups > 0) "已合并 $groups 组、共 $count 条日程"
            else "提醒日期和设置一致的日程至少要选 2 条",
            Toast.LENGTH_SHORT
        ).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("统一通知") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
                    }
                )
            },
            bottomBar = {
                if (compatibleBuckets.isNotEmpty()) {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { toggleAll(selected.size != allCandidateIds.size) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (selected.size == allCandidateIds.size) "取消全选" else "全选")
                            }
                            Button(
                                onClick = { mergeSelected() },
                                enabled = selected.size >= 2,
                                modifier = Modifier.weight(1f)
                            ) { Text("合并选中（${selected.size}）") }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCard(
                    "合并同时触发的提醒",
                    "日期、提前天数和推送时刻一致时，合并后只发送一条 Telegram 消息。"
                ) {
                    Text(
                        "合并不改动日程本身，也不会让任何一条漏掉提醒——" +
                            "服务端会在同一时刻合并推送，随时可以解散。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ── 已合并的组 ──
                if (mergeGroups.isNotEmpty()) {
                    SectionCard("已合并", "${mergeGroups.size} 组") {
                        mergeGroups.forEach { group ->
                            val members = events.filter { it.id in group.eventIds }
                            if (members.isEmpty()) return@forEach
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        members.first().dateText() + " · ${members.size} 件",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        members.joinToString("、") { it.title },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = {
                                    viewModel.unmergeNotifications(group.id)
                                    Toast.makeText(context, "已解散该合并组", Toast.LENGTH_SHORT).show()
                                }) { Text("解散") }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                // ── 自动检测出的兼容提醒 ──
                if (compatibleBuckets.isEmpty()) {
                    SectionCard("没有可合并的日程", "目前没有两条日程的日期和提醒设置完全一致") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsPaused, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "循环日程按下一次发生的日期计算，日期变了这里会自动更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    compatibleBuckets.forEach { (key, dayEvents) ->
                        val date = LocalDate.ofEpochDay(key.occurrenceEpochDay)
                        val dayIds = dayEvents.map { it.id }.toSet()
                        val chosen = dayIds.count { it in selected }
                        val dayState = when (chosen) {
                            0 -> ToggleableState.Off
                            dayIds.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val days = dayEvents.first().daysFromToday()
                        val timing = key.remindDaysBefore.joinToString("、") {
                            if (it == 0) "当天" else "提前 ${it} 天"
                        }
                        val distance = when {
                            days == 0 -> "就是今天"
                            days > 0 -> "还有 $days 天"
                            else -> "已过去 ${-days} 天"
                        }
                        SectionCard(
                            "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth),
                            "$distance · $timing · %02d:%02d · Telegram · ${dayEvents.size} 件".format(
                                key.remindHour,
                                key.remindMinute
                            )
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .triStateToggleable(dayState) {
                                        selected = if (dayState == ToggleableState.On)
                                            selected - dayIds else selected + dayIds
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TriStateCheckbox(state = dayState, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text("这一组全选", Modifier.weight(1f))
                            }
                            HorizontalDivider()
                            dayEvents.forEach { event ->
                                val checked = event.id in selected
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = checked) { on ->
                                            selected =
                                                if (on) selected + event.id else selected - event.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            event.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            buildString {
                                                append(
                                                    "%02d:%02d".format(
                                                        event.remindHour, event.remindMinute
                                                    )
                                                )
                                                if (event.id in mergedIds) append(" · 已在合并组")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
