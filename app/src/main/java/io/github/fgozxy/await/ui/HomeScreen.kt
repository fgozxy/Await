package io.github.fgozxy.await.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.data.Event
import io.github.fgozxy.await.ui.theme.EventColors
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.widget.Toast

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
    showFullScreenBanner: Boolean = false,
    onRequestFullScreen: () -> Unit = {},
    onTestNotification: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Event?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Event?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 备份与恢复 / 导入日程
    var showBackup by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    // 分组管理：selectedForDelete 是勾选集合，空串 "" 代表「未分组」这个默认分组
    var showManageGroups by remember { mutableStateOf(false) }
    var selectedForDelete by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteGroups by remember { mutableStateOf<Set<String>?>(null) }
    var newGroupName by remember { mutableStateOf("") }

    // 批量分配：对话框开关 / 目标分组 / 勾选的日程 id
    var showAssign by remember { mutableStateOf(false) }
    var assignTarget by remember { mutableStateOf<String?>(null) }
    var assignSelection by remember { mutableStateOf(setOf<Long>()) }

    // 分组筛选：null=全部，""=未分组，其他=指定分组名
    var selectedGroup by remember { mutableStateOf<String?>(null) }

    val matched = events.filter {
        query.isBlank() || it.title.contains(query, true) || it.note.contains(query, true)
    }
    // 现有分组列表：显式建过的空分组也在内，所以取 ViewModel 而不是从日程现推
    val existingGroups by viewModel.groups.collectAsStateWithLifecycle()
    val hasUngrouped = events.any { it.group.isBlank() }

    val filtered = matched.filter {
        selectedGroup == null || it.group == selectedGroup
    }
    val groups = groupEvents(filtered)

    val snackbarHostState = remember { SnackbarHostState() }

    // 左滑删除：先删掉，再给一条可撤销的提示。撤销走 upsert，日程和它的闹钟一起回来
    fun deleteWithUndo(event: Event) {
        viewModel.delete(event.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已删除「${event.title}」",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.upsert(event)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        DropdownMenuItem(
                            text = { Text("导入日程") },
                            onClick = { menuOpen = false; showImport = true },
                            leadingIcon = { Icon(Icons.Default.MoveToInbox, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("备份与恢复") },
                            onClick = { menuOpen = false; showBackup = true },
                            leadingIcon = { Icon(Icons.Default.CloudSync, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("管理分组") },
                            onClick = { menuOpen = false; showManageGroups = true },
                            leadingIcon = { Icon(Icons.Default.Label, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("分配日程到分组") },
                            onClick = {
                                menuOpen = false
                                assignTarget = null
                                assignSelection = emptySet()
                                showAssign = true
                            },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }
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
            if (showFullScreenBanner) {
                HealthBanner(
                    "未授予全屏提醒权限，闹钟到点可能不会弹出",
                    "去授权", onRequestFullScreen,
                    container = MaterialTheme.colorScheme.errorContainer
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
                    onLongClick = { deleting = it },
                    onSwipeDelete = { deleteWithUndo(it) })
            }
        }
    }

    if (creating || editing != null) {
        // key 保证切换/重开日程时表单状态完全重建，避免残留上一次的输入
        key(editing?.id, creating) {
            EditEventSheet(
                initial = editing,
                availableGroups = existingGroups,
                onDismiss = { creating = false; editing = null },
                onSave = { viewModel.upsert(it); creating = false; editing = null },
                onDelete = {
                    editing?.let { viewModel.delete(it.id) }
                    creating = false; editing = null
                }
            )
        }
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

    // ── 管理分组对话框（多选 + 全选）──
    if (showManageGroups) {
        // 分组条目：名称 → 日程数。空串条目代表「未分组」，只在确实有未分组日程时出现。
        // 名称取自 existingGroups 而不是现推，这样刚建的空分组（0 条）也会列出来
        val counts = events.filter { it.group.isNotBlank() }.groupingBy { it.group }.eachCount()
        val groupEntries = buildList {
            addAll(existingGroups.map { it to (counts[it] ?: 0) })
            if (hasUngrouped) add("" to events.count { it.group.isBlank() })
        }
        val allNames = groupEntries.map { it.first }.toSet()

        fun closeManage() {
            showManageGroups = false
            selectedForDelete = emptySet()
            newGroupName = ""
        }

        fun submitNewGroup() {
            val name = newGroupName.trim()
            if (name.isEmpty()) return
            if (viewModel.createGroup(name)) {
                newGroupName = ""
                Toast.makeText(context, "已新建分组「$name」", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "分组「$name」已存在", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog(
            onDismissRequest = { closeManage() },
            title = { Text("管理分组") },
            text = {
                Column {
                    // 新建分组：让「新建/编辑倒数日」不再是唯一入口，
                    // 也允许先把分组建好、之后再往里放日程
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text("新建分组") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { submitNewGroup() },
                            enabled = newGroupName.isBlank().not()
                        ) { Text("添加") }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (groupEntries.isEmpty()) {
                        Text("暂无分组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // 全选行：部分勾选时显示为不确定态
                        val allState = when (selectedForDelete.size) {
                            0 -> ToggleableState.Off
                            allNames.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .triStateToggleable(allState) {
                                    selectedForDelete =
                                        if (allState == ToggleableState.On) emptySet() else allNames
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TriStateCheckbox(state = allState, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text("全选", Modifier.weight(1f))
                            Text(
                                "${groupEntries.size} 个分组",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            groupEntries.forEach { (name, count) ->
                                val checked = name in selectedForDelete
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = checked) { on ->
                                            selectedForDelete =
                                                if (on) selectedForDelete + name
                                                else selectedForDelete - name
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (name.isBlank()) "未分组（默认）" else name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            "$count 条日程",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDeleteGroups = selectedForDelete },
                    enabled = selectedForDelete.isNotEmpty()
                ) {
                    Text(
                        "删除选中（${selectedForDelete.size}）",
                        color = if (selectedForDelete.isEmpty()) Color.Unspecified
                        else MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = { TextButton(onClick = { closeManage() }) { Text("完成") } }
        )
    }

    // ── 分配日程到分组（多选 + 全选）──
    // 两步走：先选目标分组，再勾日程。合在一屏里会让本来就不宽的对话框挤成一团
    if (showAssign) {
        fun closeAssign() {
            showAssign = false
            assignTarget = null
            assignSelection = emptySet()
        }

        val target = assignTarget
        val targetLabel = target?.let { if (it.isBlank()) "未分组" else it } ?: ""

        AlertDialog(
            onDismissRequest = { closeAssign() },
            title = { Text(if (target == null) "分配到哪个分组" else "选择日程 → 「$targetLabel」") },
            text = {
                if (target == null) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (existingGroups.isEmpty()) {
                            Text(
                                "还没有分组。可以先到「管理分组」里新建一个，" +
                                    "或者把日程移出分组。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        existingGroups.forEach { name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { assignTarget = name }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Label, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(name, Modifier.weight(1f))
                                Text(
                                    "${events.count { it.group == name }} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { assignTarget = "" }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("未分组（把日程移出分组）", Modifier.weight(1f))
                        }
                    }
                } else if (events.isEmpty()) {
                    Text("还没有日程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column {
                        val allIds = events.map { it.id }.toSet()
                        val allState = when (assignSelection.size) {
                            0 -> ToggleableState.Off
                            allIds.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .triStateToggleable(allState) {
                                    assignSelection =
                                        if (allState == ToggleableState.On) emptySet() else allIds
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TriStateCheckbox(state = allState, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text("全选", Modifier.weight(1f))
                            Text(
                                "${events.size} 条日程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            events.forEach { event ->
                                val checked = event.id in assignSelection
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = checked) { on ->
                                            assignSelection =
                                                if (on) assignSelection + event.id
                                                else assignSelection - event.id
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
                                            // 已经在目标分组里的标出来，免得白勾一遍
                                            if (event.group == target) "已在此分组"
                                            else if (event.group.isBlank()) "未分组"
                                            else event.group,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (target != null) {
                    TextButton(
                        enabled = assignSelection.isNotEmpty(),
                        onClick = {
                            val n = viewModel.assignGroup(assignSelection, target)
                            closeAssign()
                            Toast.makeText(
                                context,
                                if (n > 0) "已把 $n 条日程分配到「$targetLabel」" else "所选日程本来就在该分组",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) { Text("分配（${assignSelection.size}）") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 选完分组还能退回上一步换一个，不用整个重开
                    if (target != null) { assignTarget = null; assignSelection = emptySet() }
                    else closeAssign()
                }) { Text(if (target != null) "上一步" else "取消") }
            }
        )
    }

    // 批量删除二次确认
    confirmDeleteGroups?.let { sel ->
        val affected = events.count { it.group in sel }
        val hasDefault = "" in sel
        // 「未分组」没有分组可移出，唯一说得通的删除方式就是连日程一起删
        var alsoDeleteEvents by remember(sel) { mutableStateOf(hasDefault) }
        val deleteEvents = hasDefault || alsoDeleteEvents
        val names = sel.sorted().joinToString("") {
            if (it.isBlank()) "「未分组」" else "「$it」"
        }

        AlertDialog(
            onDismissRequest = { confirmDeleteGroups = null },
            title = { Text("删除 ${sel.size} 个分组？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (affected == 0) "$names 下暂无日程。" else "$names 共 $affected 条日程。")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = deleteEvents, enabled = !hasDefault) {
                                alsoDeleteEvents = it
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteEvents,
                            onCheckedChange = null,
                            enabled = !hasDefault
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "同时删除这些日程（否则仅移出分组，日程保留）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (hasDefault) {
                        Text(
                            "「未分组」是默认分组，没有分组可移出，只能连同日程一起删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = viewModel.deleteGroups(sel, deleteEvents)
                    if (selectedGroup in sel) selectedGroup = null
                    confirmDeleteGroups = null
                    selectedForDelete = emptySet()
                    showManageGroups = false
                    Toast.makeText(
                        context,
                        // 空分组删掉时 n 为 0，报「已删除 0 条日程」会让人以为没删掉
                        when {
                            n == 0 -> "已删除 ${sel.size} 个分组"
                            deleteEvents -> "已删除 $n 条日程"
                            else -> "已将 $n 条日程移出分组"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton({ confirmDeleteGroups = null }) { Text("取消") }
            }
        )
    }

    // ── 备份与恢复 ──
    if (showBackup) {
        BackupScreen(viewModel = viewModel, onDismiss = { showBackup = false })
    }

    // ── 导入日程 ──
    if (showImport) {
        ImportScreen(viewModel = viewModel, onDismiss = { showImport = false })
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

/**
 * 左滑删除的包装：滑到底即触发 [onDelete]，滑出过程中露出红色底衬。
 *
 * 只允许从右往左滑——从左往右留给系统的返回手势，两者抢同一片区域会互相打架。
 */
@Composable
private fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        },
        // 要滑过卡片宽度的一半才算数，避免列表滚动时蹭一下就误删
        positionalThreshold = { it * 0.5f }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { content() }
}

/** 列表按分组渲染 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventList(
    groups: Groups,
    viewModel: EventViewModel,
    onClick: (Event) -> Unit,
    onLongClick: (Event) -> Unit,
    onSwipeDelete: (Event) -> Unit
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
                SwipeToDeleteBox(onDelete = { onSwipeDelete(event) }) {
                    EventCard(
                        event = event,
                        onClick = { onClick(event) },
                        onLongClick = { onLongClick(event) },
                        onTogglePin = { viewModel.togglePin(event.id) }
                    )
                }
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
                            if (event.group.isNotBlank()) append("【${event.group}】 ")
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
