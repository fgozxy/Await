package io.github.fgozxy.await.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.data.Cycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.ui.theme.EventColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val REMIND_OPTIONS = listOf(
    0 to "当天", 1 to "提前1天", 3 to "提前3天", 7 to "提前7天",
    15 to "提前15天", 30 to "提前30天"
)

/** 分组快捷预设 */
private val GROUP_PRESETS = listOf("订阅", "生日", "纪念日", "工作", "学习")

/** 新建 / 编辑日程的底部弹窗 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditEventSheet(
    initial: Event?,
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var dateEpochDay by remember { mutableStateOf(initial?.dateEpochDay ?: LocalDate.now().toEpochDay()) }
    var pinned by remember { mutableStateOf(initial?.pinned ?: false) }
    var cycle by remember { mutableStateOf(initial?.cycle ?: Cycle.NONE) }
    var group by remember { mutableStateOf(initial?.groupName ?: "") }
    var everyDays by remember {
        mutableIntStateOf(initial?.repeatEveryDays?.takeIf { it > 0 } ?: 30)
    }
    var colorIndex by remember { mutableIntStateOf(initial?.colorIndex ?: 0) }
    var remindDays by remember {
        // 默认「当天 + 提前1天」都提醒，避免只提前1天而日程就在今天时错过提醒
        mutableStateOf(initial?.remindDaysBefore?.toSet() ?: setOf(0, 1))
    }
    var remindHour by remember { mutableIntStateOf(initial?.remindHour ?: 9) }
    var remindMinute by remember { mutableIntStateOf(initial?.remindMinute ?: 0) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (initial == null) "新建倒数日" else "编辑倒数日",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; error = false },
                label = { Text("标题 *") },
                isError = error,
                supportingText = { if (error) Text("请填写标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 日期选择行
            Surface(
                onClick = { showDatePicker = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("目标日期", style = MaterialTheme.typography.labelMedium)
                        Text(LocalDate.ofEpochDay(dateEpochDay).toString(),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    Text("点击选择", style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            // 分组
            Column {
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组（可留空）") },
                    placeholder = { Text("如：订阅、生日…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GROUP_PRESETS.forEach { preset ->
                        AssistChip(
                            onClick = { group = preset },
                            label = { Text(preset) }
                        )
                    }
                }
            }

            // 颜色选择
            Column {
                Text("标记颜色", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EventColors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(c, CircleShape)
                                .then(
                                    if (i == colorIndex) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable { colorIndex = i }
                        )
                    }
                }
            }

            // 开关项
            ToggleRow("置顶显示", pinned) { pinned = it }

            // 循环周期选择（订阅缴费、房租、会员到期等）
            Column {
                Text("循环周期", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cycle.entries.forEach { c ->
                        FilterChip(
                            selected = cycle == c,
                            onClick = { cycle = c },
                            label = {
                                Text(
                                    if (c == Cycle.EVERY_N_DAYS && everyDays > 0)
                                        "每隔${everyDays}天" else c.label
                                )
                            }
                        )
                    }
                }
                if (cycle != Cycle.NONE) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "倒计时和提醒将随周期自动滚动，无需手动更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 「每隔 N 天」自定义天数调节器
                if (cycle == Cycle.EVERY_N_DAYS) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedIconButton(onClick = { if (everyDays > 1) everyDays-- }) {
                            Icon(Icons.Default.Remove, "减少")
                        }
                        Text(
                            "$everyDays 天",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.widthIn(min = 72.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        OutlinedIconButton(onClick = { if (everyDays < 3650) everyDays++ }) {
                            Icon(Icons.Default.Add, "增加")
                        }
                        Spacer(Modifier.width(4.dp))
                        listOf(14, 30, 60, 90).forEach { preset ->
                            AssistChip(
                                onClick = { everyDays = preset },
                                label = { Text("$preset") }
                            )
                        }
                    }
                }
            }

            // 提醒设置
            Column {
                Text("提醒时机（可多选）", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REMIND_OPTIONS.forEach { (days, label) ->
                        FilterChip(
                            selected = days in remindDays,
                            onClick = {
                                remindDays =
                                    if (days in remindDays) remindDays - days
                                    else remindDays + days
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { showTimePicker = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("提醒时刻", style = MaterialTheme.typography.labelMedium)
                            Text("%02d:%02d".format(remindHour, remindMinute),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text("点击选择", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 操作按钮
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (initial != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "删除",
                            tint = MaterialTheme.colorScheme.error)
                    }
                } else Spacer(Modifier.width(48.dp))

                Button(
                    onClick = {
                        if (title.isBlank()) { error = true; return@Button }
                        onSave(
                            Event(
                                id = initial?.id ?: System.currentTimeMillis(),
                                title = title.trim(),
                                dateEpochDay = dateEpochDay,
                                note = note.trim(),
                                pinned = pinned,
                                colorIndex = colorIndex,
                                remindDaysBefore = (remindDays.ifEmpty { setOf(0) }).toList().sorted(),
                                remindHour = remindHour,
                                remindMinute = remindMinute,
                                repeatCycle = cycle,
                                repeatEveryDays = if (cycle == Cycle.EVERY_N_DAYS) everyDays else 0,
                                repeatYearly = false,
                                groupName = group.trim()
                            )
                        )
                    },
                    enabled = !error
                ) { Text("保存") }
            }
        }
    }

    // 日期选择对话框
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateEpochDay * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        // DatePicker 返回 UTC 毫秒，转 epoch day 需按 UTC 取日期再回本地
                        dateEpochDay = Instant.ofEpochMilli(it)
                            .atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state)
        }
    }

    // 时间选择对话框
    if (showTimePicker) {
        val timeState = rememberTimePickerState(remindHour, remindMinute, true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择提醒时刻") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    remindHour = timeState.hour
                    remindMinute = timeState.minute
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton({ showTimePicker = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
