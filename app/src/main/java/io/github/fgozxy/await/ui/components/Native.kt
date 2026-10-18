package io.github.fgozxy.await.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fgozxy.await.ui.Nav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ───────────────────────────── 触感反馈 ─────────────────────────────

/** 跟随系统「触摸反馈」设置的轻量震动。 */
class Haptics(private val view: View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )
    fun reject() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    )
    fun longPress() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * 点击时带水波纹和轻微按压回弹，像原生卡片那样跟手；[onLongClick] 附带长按震动。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed && enabled) scale else 1f,
        spring(dampingRatio = 0.6f, stiffness = 900f), label = "press"
    )
    val haptics = rememberHaptics()
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongClick?.let { { haptics.longPress(); it() } },
            onClick = onClick
        )
}

// ───────────────────────────── 底部提示 ─────────────────────────────

/** 全局 Snackbar：页面关闭后提示仍然保留（如删除后的「撤销」）。 */
class Toaster(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(message, action, withDismissAction = action != null)
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("Toaster 未提供") }

// ───────────────────────────── 页面框架 ─────────────────────────────

/**
 * 二级页面骨架：大标题顶栏随滚动收起，左侧返回键，提示条从底部弹出。
 * [bottomInset] = false 用于底部导航栏之上的标签页，避免重复留出系统导航栏高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navIcon: ImageVector? = Icons.AutoMirrored.Filled.ArrowBack,
    navLabel: String = "返回",
    onNav: () -> Unit = Nav::back,
    large: Boolean = true,
    bottomInset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    topBar: (@Composable (TopAppBarScrollBehavior) -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val behavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    else TopAppBarDefaults.pinnedScrollBehavior()
    val insets = if (bottomInset) ScaffoldDefaults.contentWindowInsets
    else ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        contentWindowInsets = insets,
        snackbarHost = { SnackbarHost(LocalToaster.current.host) },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        topBar = {
            if (topBar != null) topBar(behavior) else {
                val nav: @Composable () -> Unit = {
                    if (navIcon != null) IconButton(onClick = onNav) { Icon(navIcon, navLabel) }
                }
                val text: @Composable () -> Unit = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (large) LargeTopAppBar(title = text, navigationIcon = nav, actions = actions, scrollBehavior = behavior)
                else TopAppBar(title = text, navigationIcon = nav, actions = actions, scrollBehavior = behavior)
            }
        },
        content = content
    )
}

/** 设置类页面的滚动内容区：左右 16dp 边距，分组之间留白。 */
@Composable
fun PageColumn(
    padding: PaddingValues,
    spacing: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

// ───────────────────────────── 设置列表 ─────────────────────────────

/** 一组圆角设置项，标题用主题色小字，和系统设置一致。 */
@Composable
fun PrefGroup(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }
    }
}

@Composable
fun PrefRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    subtitleIsError: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val titleColor = when {
        destructive -> colors.error
        else -> colors.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (destructive) colors.error else colors.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleIsError) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 180.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** 整行可点的开关项。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    subtitleIsError: Boolean = false
) {
    val haptics = rememberHaptics()
    PrefRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        subtitleIsError = subtitleIsError,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) {
            haptics.tick(); onChange(it)
        },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
    )
}

/** 圆角提示卡：说明（主题色）或错误（错误色）。 */
@Composable
fun StatusCard(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ───────────────────────────── 弹层 ─────────────────────────────

/** 底部单选弹层：选中后自动收起。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetTitle(title)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(value == selected, role = Role.RadioButton) {
                                haptics.tick()
                                scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); onSelect(value) }
                            }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        RadioButton(selected = value == selected, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 系统风格的确认框；[destructive] 时确认按钮用错误色。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String = "取消",
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } }
    )
}

/** 时间选择框：表盘 / 键盘输入可切换，和系统时钟一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    confirm: String = "确定",
    enabled: Boolean = true,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(hour, minute, is24Hour = true)
    var typing by remember { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = { if (enabled) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (typing) TimeInput(state) else TimePicker(state)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { typing = !typing }) {
                        Icon(
                            if (typing) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                            if (typing) "表盘选择" else "键盘输入"
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = enabled, onClick = onDismiss) { Text("取消") }
                    TextButton(enabled = enabled, onClick = { onConfirm(state.hour, state.minute) }) { Text(confirm) }
                }
            }
        }
    }
}
