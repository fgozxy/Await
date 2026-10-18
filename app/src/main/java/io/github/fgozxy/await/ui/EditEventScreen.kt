package io.github.fgozxy.await.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Cycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.data.LunarDates
import io.github.fgozxy.await.notify.ReminderSettings
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.ui.theme.EventColors
import io.github.fgozxy.await.vm.EventViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val REMIND_OPTIONS = listOf(
    0 to "当天", 1 to "提前1天", 3 to "提前3天", 7 to "提前7天",
    15 to "提前15天", 30 to "提前30天"
)

/** 分组快捷预设 */
private val GROUP_PRESETS = listOf("订阅", "生日", "纪念日", "工作", "学习")

/**
 * 新建 / 编辑日程的整页表单。[eventId] 为 null 时新建。
 * 有未保存的修改时，返回会先确认是否放弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(viewModel: EventViewModel, eventId: Long?) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    val existingGroups by viewModel.groups.collectAsStateWithLifecycle()
    val initial = remember(eventId) { eventId?.let { id -> viewModel.events.value.firstOrNull { it.id == id } } }
    val defaultTime = remember { ReminderSettings.load(context) }

    var title by rememberSaveable { mutableStateOf(initial?.title ?: "") }
    var note by rememberSaveable { mutableStateOf(initial?.note ?: "") }
    var dateEpochDay by rememberSaveable { mutableLongStateOf(initial?.dateEpochDay ?: LocalDate.now().toEpochDay()) }
    var isLunar by rememberSaveable { mutableStateOf(initial?.isLunar ?: false) }
    var pinned by rememberSaveable { mutableStateOf(initial?.pinned ?: false) }
    var cycle by rememberSaveable { mutableStateOf(initial?.cycle ?: Cycle.NONE) }
    // 循环间隔数：N=1 表示「每」；N>1 表示「每隔N个单位」
    var repN by rememberSaveable { mutableIntStateOf(initial?.repeatN?.takeIf { it > 1 } ?: 1) }
    var group by rememberSaveable { mutableStateOf(initial?.group ?: "") }
    var colorIndex by rememberSaveable { mutableIntStateOf(initial?.colorIndex ?: 0) }
    var remindDays by rememberSaveable {
        mutableStateOf(initial?.remindDaysBefore?.toSet() ?: setOf(Event.DEFAULT_REMIND_DAYS))
    }
    var preciseTime by rememberSaveable { mutableStateOf(initial?.preciseTime ?: false) }
    var remindHour by rememberSaveable { mutableIntStateOf(initial?.remindHour ?: defaultTime.hour) }
    var remindMinute by rememberSaveable { mutableIntStateOf(initial?.remindMinute ?: defaultTime.minute) }
    var titleError by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    fun draft() = Event(
        id = initial?.id ?: 0L,
        title = title.trim(),
        dateEpochDay = dateEpochDay,
        calendarType = if (isLunar) "LUNAR" else null,
        note = note.trim(),
        pinned = pinned,
        colorIndex = colorIndex,
        remindDaysBefore = remindDays.toList().sorted(),
        remindHour = remindHour,
        remindMinute = remindMinute,
        preciseTime = preciseTime,
        repeatSpec = if (cycle == Cycle.NONE) null else "${cycle.name}:$repN",
        groupName = group.trim()
    )

    // 和打开时的内容比较，判断有没有未保存的修改
    val baseline = remember { draft() }
    val dirty = draft() != baseline

    fun close() { if (dirty) confirmDiscard = true else Nav.back() }

    fun save() {
        if (title.isBlank()) {
            titleError = true
            haptics.reject()
            titleFocus.requestFocus()
            return
        }
        viewModel.upsert(draft().copy(id = initial?.id ?: System.currentTimeMillis()))
        haptics.confirm()
        Nav.back()
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }
    // 新建时直接聚焦标题并弹出键盘；等滑入动画走完再聚焦，避免键盘顶着动画跳
    if (initial == null) LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        titleFocus.requestFocus()
    }

    PageScaffold(
        title = if (initial == null) "新建倒数日" else "编辑倒数日",
        large = false,
        navIcon = Icons.Default.Close,
        navLabel = "关闭",
        onNav = ::close,
        actions = {
            Button(onClick = ::save, modifier = Modifier.padding(end = 8.dp)) { Text("保存") }
        }
    ) { padding ->
        PageColumn(padding, spacing = 16.dp) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text("标题") },
                placeholder = { Text("如：妈妈生日、房租") },
                isError = titleError,
                supportingText = if (titleError) { { Text("请填写标题") } } else null,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
            )

            val preview = draft()
            PrefGroup {
                val date = LocalDate.ofEpochDay(dateEpochDay)
                PrefRow(
                    title = if (isLunar) LunarDates.text(date) else solarText(date),
                    subtitle = (if (isLunar) "公历 ${solarText(date)} · " else "") + daysText(preview.daysFromToday()),
                    icon = Icons.Outlined.CalendarMonth,
                    value = if (isLunar) "农历" else "公历",
                    onClick = { sheet = "date" }
                )
                PrefRow(
                    title = "循环",
                    icon = Icons.Outlined.Repeat,
                    value = cycleText(cycle, repN, isLunar),
                    onClick = { sheet = "cycle" }
                )
            }

            PrefGroup {
                PrefRow(
                    title = "提醒",
                    icon = Icons.Outlined.Notifications,
                    value = remindText(remindDays),
                    onClick = { sheet = "remind" }
                )
                SwitchRow(
                    title = "精准时间",
                    subtitle = if (preciseTime) "单独设置这条日程的提醒时刻" else "跟随默认通知时间 ${defaultTime.display()}",
                    icon = Icons.Outlined.Timer,
                    checked = preciseTime,
                    onChange = { preciseTime = it }
                )
                if (preciseTime) {
                    PrefRow(
                        title = "提醒时刻",
                        icon = Icons.Outlined.AccessTime,
                        value = "%02d:%02d".format(remindHour, remindMinute),
                        onClick = { sheet = "time" }
                    )
                }
            }

            PrefGroup {
                PrefRow(
                    title = "分组",
                    icon = Icons.Outlined.Folder,
                    value = group.ifBlank { "未分组" },
                    onClick = { sheet = "group" }
                )
                ColorRow(colorIndex) { haptics.tick(); colorIndex = it }
                SwitchRow(title = "置顶", icon = Icons.Outlined.PushPin, checked = pinned, onChange = { pinned = it })
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                minLines = 3,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (initial != null) {
                PrefGroup {
                    PrefRow(
                        title = "删除日程",
                        icon = Icons.Outlined.Delete,
                        destructive = true,
                        onClick = { confirmDelete = true }
                    )
                }
            }
        }
    }

    when (sheet) {
        "date" -> EventDatePicker(
            date = LocalDate.ofEpochDay(dateEpochDay),
            isLunar = isLunar,
            onDismiss = { sheet = null },
            onConfirm = { selected, lunar ->
                dateEpochDay = selected.toEpochDay()
                isLunar = lunar
                sheet = null
            }
        )
        "cycle" -> CycleSheet(cycle, repN, isLunar, onDismiss = { sheet = null }) { c, n -> cycle = c; repN = n }
        "remind" -> RemindSheet(remindDays, onDismiss = { sheet = null }) { remindDays = it }
        "time" -> TimePickerDialog(
            title = "提醒时刻",
            hour = remindHour,
            minute = remindMinute,
            onDismiss = { sheet = null }
        ) { h, m -> remindHour = h; remindMinute = m; sheet = null }
        "group" -> MoveToGroupSheet(
            groups = existingGroups,
            suggestions = GROUP_PRESETS.filter { p -> existingGroups.none { it.equals(p, true) } },
            current = group.trim(),
            title = "选择分组",
            onDismiss = { sheet = null },
            // 新分组随日程一起保存，放弃编辑就不会留下空分组
            onCreate = { name -> existingGroups.none { it.equals(name, ignoreCase = true) } }
        ) { group = it }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "放弃修改？",
            text = "未保存的内容将会丢失。",
            confirm = "放弃",
            dismiss = "继续编辑",
            destructive = true,
            onDismiss = { confirmDiscard = false }
        ) { Nav.back() }
    }
    if (confirmDelete && initial != null) {
        ConfirmDialog(
            title = "删除「${initial.title}」？",
            text = "对应的提醒也会一并取消。",
            confirm = "删除",
            destructive = true,
            onDismiss = { confirmDelete = false }
        ) {
            viewModel.delete(initial.id)
            haptics.confirm()
            Nav.back()
            toaster.show("已删除「${initial.title}」", "撤销") { viewModel.upsert(initial) }
        }
    }
}

private fun solarText(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日 " +
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)

private fun daysText(days: Int): String = when {
    days == 0 -> "就是今天"
    days > 0 -> "还有 $days 天"
    else -> "已过去 ${-days} 天"
}

private fun cycleText(cycle: Cycle, n: Int, lunar: Boolean): String = when {
    cycle == Cycle.NONE -> "不重复"
    else -> (if (lunar && cycle in listOf(Cycle.MONTH, Cycle.YEAR)) "农历" else "") +
        (if (n <= 1) "每${cycle.unit}" else "每 $n ${cycle.unit}")
}

private fun remindText(days: Set<Int>): String =
    if (days.isEmpty()) "不提醒"
    else REMIND_OPTIONS.filter { it.first in days }.joinToString("、") { it.second }

@Composable
private fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text("颜色", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EventColors.forEachIndexed { i, c ->
                val ring by animateDpAsState(if (i == selected) 2.dp else 0.dp, label = "ring")
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding(if (i == selected) 4.dp else 0.dp)
                        .clip(CircleShape)
                        .background(c)
                        .clickable(role = Role.RadioButton) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    if (i == selected) Icon(Icons.Default.Check, "已选", Modifier.size(14.dp),
                        tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** 循环周期：单位分段按钮 + 间隔步进器 + 常用间隔。改动即时生效，收起即完成。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CycleSheet(
    cycle: Cycle,
    repN: Int,
    isLunar: Boolean,
    onDismiss: () -> Unit,
    onChange: (Cycle, Int) -> Unit
) {
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("循环", style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Cycle.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = cycle == c,
                        onClick = { haptics.tick(); onChange(c, if (c == cycle) repN else 1) },
                        shape = SegmentedButtonDefaults.itemShape(i, Cycle.entries.size),
                        icon = {},
                        label = { Text(if (c == Cycle.NONE) "不重复" else c.unit, maxLines = 1) }
                    )
                }
            }
            if (cycle != Cycle.NONE) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilledTonalIconButton(onClick = { if (repN > 1) { haptics.tick(); onChange(cycle, repN - 1) } }) {
                        Icon(Icons.Default.Remove, "减少")
                    }
                    Text(
                        if (repN <= 1) "每${cycle.unit}" else "每 $repN ${cycle.unit}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                    FilledTonalIconButton(onClick = { if (repN < 3650) { haptics.tick(); onChange(cycle, repN + 1) } }) {
                        Icon(Icons.Default.Add, "增加")
                    }
                }
                val presets = when (cycle) {
                    Cycle.WEEK -> listOf(1, 2, 4)
                    Cycle.MONTH -> listOf(1, 2, 3, 6)
                    Cycle.YEAR -> listOf(1, 2, 4, 10)
                    else -> listOf(1, 7, 14, 30, 90)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = repN == preset,
                            onClick = { onChange(cycle, preset) },
                            label = { Text(if (preset == 1) "每${cycle.unit}" else "每$preset${cycle.unit}") }
                        )
                    }
                }
                Text(
                    if (isLunar && cycle in listOf(Cycle.MONTH, Cycle.YEAR))
                        "按农历循环；小月取月末，闰月在无对应闰月的年份按同名普通月提醒"
                    else "倒计时和提醒将随周期自动滚动，无需手动更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

/** 提醒时机：多选，全部取消则不提醒。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindSheet(days: Set<Int>, onDismiss: () -> Unit, onChange: (Set<Int>) -> Unit) {
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("提醒", "可多选；全部取消则不提醒。通知渠道在「设置 → 通知」中选择")
            Column(Modifier.verticalScroll(rememberScrollState())) {
                REMIND_OPTIONS.forEach { (d, label) ->
                    val checked = d in days
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(checked, role = Role.Checkbox) {
                                haptics.tick(); onChange(if (it) days + d else days - d)
                            }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("完成")
            }
        }
    }
}
