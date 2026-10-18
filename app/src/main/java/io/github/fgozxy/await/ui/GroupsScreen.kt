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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fgozxy.await.ui.components.*
import io.github.fgozxy.await.vm.EventViewModel
import kotlinx.coroutines.launch

/**
 * 「分组」标签页：点分组查看其中的日程，长按多选删除。
 * 空串 "" 代表「未分组」这个默认分组，只在确实有未分组日程时出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(viewModel: EventViewModel) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()

    var selection by remember { mutableStateOf(setOf<String>()) }
    val selecting = selection.isNotEmpty()
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    BackHandler(enabled = selecting) { selection = emptySet() }

    // 名称取自 groups 而不是现推，这样刚建的空分组（0 条）也会列出来
    val counts = events.groupingBy { it.group }.eachCount()
    val entries = buildList {
        addAll(groups.map { it to (counts[it] ?: 0) })
        if (counts.containsKey("")) add("" to (counts[""] ?: 0))
    }
    LaunchedEffect(entries.map { it.first }) {
        selection = selection intersect entries.map { it.first }.toSet()
    }

    PageScaffold(
        title = "分组",
        navIcon = null,
        bottomInset = false,
        topBar = if (selecting) { _ ->
            TopAppBar(
                title = { Text("已选 ${selection.size} 个分组") },
                navigationIcon = {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, "退出多选") }
                },
                actions = {
                    IconButton(onClick = {
                        val all = entries.map { it.first }.toSet()
                        selection = if (selection.containsAll(all)) emptySet() else all
                    }) { Icon(Icons.Outlined.SelectAll, "全选") }
                    IconButton(onClick = { confirmDelete = selection }) { Icon(Icons.Outlined.Delete, "删除") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        } else null,
        floatingActionButton = {
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, "新建分组")
                }
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(20.dp))
                Text("还没有分组", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "用分组把生日、订阅、工作分开管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(onClick = { creating = true }) { Text("新建分组") }
            }
            return@PageScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(entries.size, key = { "g_" + entries[it].first }) { i ->
                val (name, count) = entries[i]
                val selected = name in selection
                GroupRow(
                    name = if (name.isBlank()) "未分组" else name,
                    count = count,
                    icon = if (name.isBlank()) Icons.Outlined.FolderOff else Icons.Outlined.Folder,
                    selected = selected,
                    selecting = selecting,
                    modifier = Modifier.animateItem(),
                    onLongClick = { selection = selection + name }
                ) {
                    if (selecting) {
                        haptics.tick()
                        selection = if (selected) selection - name else selection + name
                    } else {
                        Nav.groupFilter.value = name
                        Nav.go(Nav.EVENTS)
                    }
                }
            }
        }
    }

    if (creating) {
        NewGroupDialog(onDismiss = { creating = false }) { name ->
            if (viewModel.createGroup(name)) {
                toaster.show("已新建分组「$name」"); true
            } else false
        }
    }

    confirmDelete?.let { sel ->
        DeleteGroupsDialog(
            names = sel,
            affected = events.count { it.group in sel },
            onDismiss = { confirmDelete = null }
        ) { deleteEvents ->
            val n = viewModel.deleteGroups(sel, deleteEvents)
            if (Nav.groupFilter.value in sel) Nav.groupFilter.value = null
            confirmDelete = null
            selection = emptySet()
            haptics.confirm()
            // 空分组删掉时 n 为 0，报「已删除 0 条日程」会让人以为没删掉
            toaster.show(
                when {
                    n == 0 -> "已删除 ${sel.size} 个分组"
                    deleteEvents -> "已删除 ${sel.size} 个分组及 $n 条日程"
                    else -> "已删除 ${sel.size} 个分组，$n 条日程移到未分组"
                }
            )
        }
    }
}

@Composable
private fun GroupRow(
    name: String,
    count: Int,
    icon: ImageVector,
    selected: Boolean,
    selecting: Boolean,
    modifier: Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow, label = "group"
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .pressable(scale = 0.98f, onLongClick = onLongClick, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (selecting && selected) colors.primary else colors.secondaryContainer)
                    .then(if (selecting && !selected) Modifier.border(1.5.dp, colors.outline, CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selecting && selected -> Icon(Icons.Default.Check, null, tint = colors.onPrimary)
                    selecting -> Unit
                    else -> Icon(icon, null, tint = colors.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("$count 条", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** 新建分组输入框；[onCreate] 返回 false 表示重名，就地提示不关闭。 */
@Composable
internal fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Boolean) {
    var name by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        val n = name.trim()
        if (n.isEmpty()) return
        if (onCreate(n)) onDismiss() else duplicate = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
        title = { Text("新建分组") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; duplicate = false },
                label = { Text("分组名称") },
                singleLine = true,
                isError = duplicate,
                supportingText = if (duplicate) { { Text("分组「${name.trim()}」已存在") } } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = name.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DeleteGroupsDialog(
    names: Set<String>,
    affected: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteEvents: Boolean) -> Unit
) {
    val hasDefault = "" in names
    // 「未分组」没有分组可移出，唯一说得通的删除方式就是连日程一起删
    var alsoDeleteEvents by remember(names) { mutableStateOf(hasDefault) }
    val deleteEvents = hasDefault || alsoDeleteEvents
    val label = names.sorted().joinToString("、") { if (it.isBlank()) "未分组" else it }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, null) },
        title = { Text("删除 ${names.size} 个分组？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (affected == 0) "「$label」下暂无日程。" else "「$label」共 $affected 条日程。")
                if (affected > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(value = deleteEvents, enabled = !hasDefault) { alsoDeleteEvents = it }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteEvents, onCheckedChange = null, enabled = !hasDefault)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasDefault) "未分组的日程会一并删除" else "同时删除这些日程（否则移到未分组）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteEvents) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 「移到分组」底部弹层：选已有分组、移出分组，或就地新建一个再移入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveToGroupSheet(
    groups: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Boolean,
    suggestions: List<String> = emptyList(),
    current: String? = null,
    title: String = "移到分组",
    onSelect: (String) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    fun pick(name: String) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); onSelect(name) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetTitle(title)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                groups.forEach { name ->
                    SheetRow(Icons.Outlined.Folder, name, selected = current == name) { pick(name) }
                }
                SheetRow(Icons.Outlined.FolderOff, "未分组", selected = current == "") { pick("") }
                if (suggestions.isNotEmpty()) {
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                    )
                    suggestions.forEach { name ->
                        SheetRow(Icons.Outlined.Folder, name, selected = current == name) { pick(name) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp, horizontal = 24.dp))
            SheetRow(Icons.Outlined.CreateNewFolder, "新建分组", accent = true) { creating = true }
        }
    }
    if (creating) {
        NewGroupDialog(onDismiss = { creating = false }) { name ->
            onCreate(name).also { if (it) pick(name) }
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    text: String,
    selected: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (accent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.weight(1f)
        )
        if (selected) Icon(Icons.Default.Check, "当前分组", tint = MaterialTheme.colorScheme.primary)
    }
}
