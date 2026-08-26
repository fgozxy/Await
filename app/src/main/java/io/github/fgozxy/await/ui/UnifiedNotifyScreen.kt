package io.github.fgozxy.await.ui

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
import io.github.fgozxy.await.vm.EventViewModel
import android.widget.Toast
import java.time.LocalDate

/**
 * 「统一通知」整页对话框。
 *
 * 自动把日程按「下一次发生的日期」归拢，同一天有两条以上的才列出来——只有这种情况
 * 才谈得上合并。勾选后建成合并组：到点只提醒一次，通知正文里带上同一天的其他事。
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

    // 按「下一次发生的日期」归拢，只留同一天两条以上的
    val sameDayBuckets: List<Pair<LocalDate, List<Event>>> = remember(events) {
        events.groupBy { it.nextOccurrence() }
            .filter { it.value.size >= 2 }
            .toList()
            .sortedBy { it.first }
    }
    val mergedIds = remember(mergeGroups) { mergeGroups.flatMap { it.eventIds }.toSet() }
    val allCandidateIds = remember(sameDayBuckets) {
        sameDayBuckets.flatMap { it.second }.map { it.id }.toSet()
    }

    fun toggleAll(on: Boolean) {
        selected = if (on) allCandidateIds else emptySet()
    }

    /** 每个日期各自成一组：跨天的日程合在一起提醒没有意义 */
    fun mergeSelected() {
        var groups = 0
        var count = 0
        sameDayBuckets.forEach { (_, dayEvents) ->
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
            else "同一天至少要选 2 条才能合并",
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
                if (sameDayBuckets.isNotEmpty()) {
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
                    "合并同一天的提醒",
                    "同一天有多件事时，到点会挨个响一遍。合并之后这一组只提醒一次，通知里一并列出。"
                ) {
                    Text(
                        "合并不改动日程本身，也不会让任何一条漏掉提醒——" +
                            "去重只发生在提醒触发的那一刻，随时可以解散。",
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

                // ── 自动检测出的同一天日程 ──
                if (sameDayBuckets.isEmpty()) {
                    SectionCard("没有可合并的日程", "目前没有任何两条日程落在同一天") {
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
                    sameDayBuckets.forEach { (date, dayEvents) ->
                        val dayIds = dayEvents.map { it.id }.toSet()
                        val chosen = dayIds.count { it in selected }
                        val dayState = when (chosen) {
                            0 -> ToggleableState.Off
                            dayIds.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val days = dayEvents.first().daysFromToday()
                        SectionCard(
                            "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth),
                            when {
                                days == 0 -> "就是今天 · ${dayEvents.size} 件"
                                days > 0 -> "还有 $days 天 · ${dayEvents.size} 件"
                                else -> "已过去 ${-days} 天 · ${dayEvents.size} 件"
                            }
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
                                Text("这一天全选", Modifier.weight(1f))
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
                                                if (event.isAlarmMode) append(" · 闹钟式")
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
