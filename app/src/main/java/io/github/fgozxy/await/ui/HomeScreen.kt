package io.github.fgozxy.await.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.ui.theme.EventColors
import io.github.fgozxy.await.vm.EventViewModel
import java.time.LocalDate

/** 首页：日程列表 + 搜索 + 新增入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: EventViewModel,
    showExactAlarmBanner: Boolean,
    onRequestExactAlarm: () -> Unit,
    showNotifBanner: Boolean = false,
    onRequestNotif: () -> Unit = {},
    showBattBanner: Boolean = false,
    onRequestBatt: () -> Unit = {},
    onTestNotification: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Event?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Event?>(null) }
    // 分组筛选：null=全部，""=未分组，其他=指定分组名
    var selectedGroup by remember { mutableStateOf<String?>(null) }

    val matched = events.filter {
        query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true)
    }
    // 现有分组列表（保持稳定排序）
    val existingGroups = events.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
    val hasUngrouped = events.any { it.groupName.isBlank() }

    val filtered = matched.filter {
        selectedGroup == null || it.groupName == selectedGroup
    }
    val groups = groupEvents(filtered)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { if (!searching) Text("Await") },
                actions = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索日程…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "搜索")
                    }
                    // ⋮ 菜单
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("发送测试通知") },
                            onClick = { menuOpen = false; onTestNotification() },
                            leadingIcon = { Icon(Icons.Default.NotificationsActive, null) }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("新建倒数日")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNotifBanner) {
                HealthBanner(
                    "通知权限被关闭，提醒将无法显示",
                    "去开启", onRequestNotif,
                    container = MaterialTheme.colorScheme.errorContainer
                )
            }
            if (showExactAlarmBanner) {
                HealthBanner(
                    "未授予精确闹钟权限，提醒可能延迟",
                    "去授权", onRequestExactAlarm
                )
            }
            if (showBattBanner) {
                HealthBanner(
                    "建议加入电池优化白名单，防止后台提醒被拦截",
                    "去设置", onRequestBatt
                )
            }
            if (filtered.isEmpty() && (query.isNotBlank() || selectedGroup != null)) {
                // 搜索/筛选无结果
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("没有匹配的日程", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { query = ""; selectedGroup = null }) {
                        Text("清除筛选")
                    }
                }
            } else if (filtered.isEmpty()) {
                EmptyState()
            } else {
                if (!searching && events.isNotEmpty()) {
                    GroupFilterRow(
                        groups = existingGroups,
                        showUngrouped = hasUngrouped,
                        selected = selectedGroup,
                        onSelect = { selectedGroup = it }
                    )
                }
                EventList(groups, viewModel,
                    onClick = { editing = it },
                    onLongClick = { deleting = it })
            }
        }
    }

    if (creating || editing != null) {
        EditEventSheet(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { viewModel.upsert(it); creating = false; editing = null },
            onDelete = {
                editing?.let { viewModel.delete(it.id) }
                creating = false; editing = null
            }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${target.title}」？") },
            text = { Text("删除后其提醒也会一并取消，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } }
        )
    }
}

/** 分组筛选行：全部 / 未分组 / 各分组 */
@Composable
private fun GroupFilterRow(
    groups: List<String>,
    showUngrouped: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("全部") }
            )
        }
        if (showUngrouped) {
            item {
                FilterChip(
                    selected = selected == "",
                    onClick = { onSelect("") },
                    label = { Text("未分组") }
                )
            }
        }
        items(groups.size, key = { groups[it] }) { i ->
            val name = groups[i]
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(name) },
                label = { Text(name) }
            )
        }
    }
}

@Composable
private fun HealthBanner(
    text: String,
    actionLabel: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.errorContainer
) {
    Surface(tonalElevation = 2.dp, color = container) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

private data class Groups(
    val pinned: List<Event>,
    val today: List<Event>,
    val upcoming: List<Event>,
    val past: List<Event>
)

private fun groupEvents(events: List<Event>): Groups {
    val (pin, rest) = events.partition { it.pinned }
    return Groups(
        pinned = pin,
        today = rest.filter { it.daysFromToday() == 0 },
        upcoming = rest.filter { it.daysFromToday() > 0 },
        past = rest.filter { it.daysFromToday() < 0 }
    )
}

/** 列表按分组渲染 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventList(
    groups: Groups,
    viewModel: EventViewModel,
    onClick: (Event) -> Unit,
    onLongClick: (Event) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOfNotNull(
            ("置顶" to groups.pinned).takeIf { it.second.isNotEmpty() },
            ("今天" to groups.today).takeIf { it.second.isNotEmpty() },
            ("即将到来" to groups.upcoming).takeIf { it.second.isNotEmpty() },
            ("已经过去" to groups.past).takeIf { it.second.isNotEmpty() }
        ).forEach { (label, list) ->
            item(key = "header_$label") {
                SectionHeader(label)
            }
            items(list.size, key = { list[it].id }) { i ->
                val event = list[i]
                EventCard(
                    event = event,
                    onClick = { onClick(event) },
                    onLongClick = { onLongClick(event) },
                    onTogglePin = { viewModel.togglePin(event.id) }
                )
            }
        }
        item { Spacer(Modifier.height(72.dp)) } // 给 FAB 留空间
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** 单条日程卡片：左侧色条 + 标题日期 + 右侧大字倒计时 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventCard(
    event: Event,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val accent = EventColors[event.colorIndex % EventColors.size]
    val days = event.daysFromToday()

    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier.width(6.dp).fillMaxHeight().background(accent)
            )
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            event.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        event.cycleLabel()?.let { label ->
                            Text("  $label", fontSize = 11.sp, color = accent)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (event.groupName.isNotBlank()) append("【${event.groupName}】 ")
                            append(event.dateText())
                            if (event.note.isNotBlank()) append("  ·  ${event.note}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    DayCounter(days, accent)
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "置顶",
                        tint = if (event.pinned) accent else Color.Transparent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp)
                            .clickable { onTogglePin() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCounter(days: Int, accent: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        when {
            days == 0 -> {
                Text("今天", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            days > 0 -> {
                Text("$days", color = accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(" 天", color = accent, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            else -> {
                Text("${-days}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(" 天前", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.EventNote, null,
            modifier = Modifier.size(84.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有倒数日", style = MaterialTheme.typography.titleMedium)
        Text(
            "点击右下角按钮，创建第一个吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
