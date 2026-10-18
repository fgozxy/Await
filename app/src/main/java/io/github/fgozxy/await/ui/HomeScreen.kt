package io.github.fgozxy.await.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.ui.theme.EventColors
import io.github.fgozxy.await.vm.EventViewModel

/** 「日程」标签页：分组筛选 + 搜索 + 列表；长按进入多选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: EventViewModel) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val existingGroups by viewModel.groups.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    val notificationHealth = rememberNotificationHealth()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroup by Nav.groupFilter
    var selection by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selection.isNotEmpty()
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 日程被别处删掉（导入覆盖、分组删除）后，多选里不再留着它们
    LaunchedEffect(events) {
        val live = events.map { it.id }.toSet()
        if (!live.containsAll(selection)) selection = selection intersect live
    }
    // 筛选的分组被删掉时回到「全部」
    LaunchedEffect(existingGroups, events) {
        val g = selectedGroup
        if (g != null && g.isNotEmpty() && g !in existingGroups) selectedGroup = null
        if (g == "" && events.none { it.group.isBlank() }) selectedGroup = null
    }

    BackHandler(enabled = selecting) { selection = emptySet() }
    BackHandler(enabled = searching && !selecting) { searching = false; query = "" }

    val hasUngrouped = events.any { it.group.isBlank() }
    val filtered = events.filter {
        (query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true)) &&
            (selectedGroup == null || it.group == selectedGroup)
    }
    val sections = groupEvents(filtered)

    fun deleteWithUndo(ids: Set<Long>) {
        val removed = viewModel.delete(ids)
        selection = emptySet()
        if (removed.isEmpty()) return
        haptics.confirm()
        toaster.show(
            if (removed.size == 1) "已删除「${removed[0].title}」" else "已删除 ${removed.size} 条日程",
            "撤销"
        ) { viewModel.upsertAll(removed) }
    }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    PageScaffold(
        title = "Await",
        navIcon = null,
        bottomInset = false,
        topBar = { behavior ->
            when {
                selecting -> TopAppBar(
                    title = { Text("已选 ${selection.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, "退出多选") }
                    },
                    actions = {
                        val chosen = events.filter { it.id in selection }
                        val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
                        IconButton(onClick = {
                            val visible = filtered.map { it.id }.toSet()
                            selection = if (selection.containsAll(visible)) emptySet() else visible
                        }) { Icon(Icons.Outlined.SelectAll, "全选") }
                        IconButton(onClick = {
                            val n = viewModel.setPinned(selection, !allPinned)
                            selection = emptySet()
                            toaster.show(if (allPinned) "已取消置顶 $n 条" else "已置顶 $n 条")
                        }) {
                            Icon(if (allPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                if (allPinned) "取消置顶" else "置顶")
                        }
                        IconButton(onClick = { moving = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "移到分组")
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
                searching -> SearchTopBar(query, { query = it }) { searching = false; query = "" }
                else -> LargeTopAppBar(
                    title = { Text("Await") },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "搜索") }
                    },
                    scrollBehavior = behavior
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { Nav.go("edit") },
                    expanded = fabExpanded,
                    // 收起成圆形按钮时也要让读屏念出用途
                    modifier = Modifier.semantics { contentDescription = "新建倒数日" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("新建倒数日") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            notificationHealth.homeWarning?.let { warning ->
                if (!searching && !selecting) item(key = "health") {
                    HealthCard(warning) { Nav.go("notifications") }
                }
            }
            if (!searching && (existingGroups.isNotEmpty() || selectedGroup != null)) {
                item(key = "filters") {
                    GroupFilterRow(existingGroups, hasUngrouped, selectedGroup) {
                        haptics.tick(); selectedGroup = it
                    }
                }
            }
            when {
                events.isEmpty() -> item(key = "empty") { EmptyState() }
                filtered.isEmpty() -> item(key = "nomatch") {
                    NoMatch { query = ""; selectedGroup = null }
                }
                else -> sections.forEach { (label, list) ->
                    item(key = "header_$label") { SectionHeader(label, list.size) }
                    items(list.size, key = { list[it].id }) { i ->
                        val event = list[i]
                        val selected = event.id in selection
                        EventCard(
                            event = event,
                            selected = selected,
                            selecting = selecting,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                if (selecting) {
                                    haptics.tick()
                                    selection = if (selected) selection - event.id else selection + event.id
                                } else Nav.go("edit", event.id.toString())
                            },
                            onLongClick = { selection = selection + event.id }
                        )
                    }
                }
            }
        }
    }

    if (moving) {
        MoveToGroupSheet(
            groups = existingGroups,
            onDismiss = { moving = false },
            onCreate = { viewModel.createGroup(it) }
        ) { target ->
            val n = viewModel.assignGroup(selection, target)
            selection = emptySet()
            val label = if (target.isBlank()) "未分组" else target
            toaster.show(if (n > 0) "已把 $n 条日程移到「$label」" else "所选日程本来就在「$label」")
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "删除 ${selection.size} 条日程？",
            text = "对应的提醒也会一并取消。",
            confirm = "删除",
            destructive = true,
            onDismiss = { confirmDelete = false }
        ) { deleteWithUndo(selection) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭搜索") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("搜索标题或备注") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "清除") }
        }
    )
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") }) }
        if (showUngrouped) item {
            FilterChip(selected = selected == "", onClick = { onSelect("") }, label = { Text("未分组") })
        }
        items(groups.size, key = { groups[it] }) { i ->
            val name = groups[i]
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(if (selected == name) null else name) },
                label = { Text(name) },
                leadingIcon = if (selected == name) {
                    { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
private fun HealthCard(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsOff, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("提醒可能无法送达", style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Text("去设置", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun groupEvents(events: List<Event>): List<Pair<String, List<Event>>> {
    val (pin, rest) = events.partition { it.pinned }
    return listOf(
        "置顶" to pin,
        "今天" to rest.filter { it.daysFromToday() == 0 },
        "即将到来" to rest.filter { it.daysFromToday() > 0 },
        "已经过去" to rest.filter { it.daysFromToday() < 0 }
    ).filter { it.second.isNotEmpty() }
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Row(
        Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "  $count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单条日程卡片：色条 + 标题日期 + 右侧大字倒计时；多选时色条换成勾选圆点。 */
@Composable
private fun EventCard(
    event: Event,
    selected: Boolean,
    selecting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accent = EventColors[Math.floorMod(event.colorIndex, EventColors.size)]
    val days = event.daysFromToday()
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow, label = "card"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .pressable(scale = 0.98f, onLongClick = onLongClick, onClick = onClick)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 24.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                if (selecting) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.primary else Color.Transparent)
                            .then(if (selected) Modifier else Modifier.border(1.5.dp, colors.outline, CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = colors.onPrimary)
                    }
                } else {
                    Box(
                        Modifier
                            .size(width = 5.dp, height = 40.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
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
                    if (event.pinned) {
                        Icon(
                            Icons.Filled.PushPin, "已置顶",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp).size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    event.dateText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tags = listOfNotNull(event.group.takeIf { it.isNotBlank() }, event.cycleLabel())
                if (tags.isNotEmpty() || event.note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        tags.forEach { Tag(it, accent) }
                        if (event.note.isNotBlank()) {
                            Text(
                                event.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            DayCounter(days, accent)
        }
    }
}

@Composable
private fun Tag(text: String, accent: Color) {
    Text(
        text,
        fontSize = 11.sp,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun DayCounter(days: Int, accent: Color) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.End) {
        when {
            days == 0 -> Text("今天", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            days > 0 -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$days", color = accent, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
                    Text(" 天", color = accent, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("还有", color = muted, fontSize = 11.sp)
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${-days}", color = muted, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                    Text(" 天", color = muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text("已过去", color = muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.EventNote, null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("还没有倒数日", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "生日、纪念日、订阅续费，都可以记在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = { Nav.go("edit") }) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("新建倒数日")
        }
    }
}

@Composable
private fun NoMatch(onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text("没有匹配的日程", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClear) { Text("清除筛选") }
    }
}
