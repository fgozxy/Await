package io.github.fgozxy.await.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 压入页面栈的一页；[id] 区分保存的页面状态，同一页面两次打开互不串数据。 */
class Route(val key: String, val arg: String = "") {
    val id: Long = seq++

    private companion object {
        var seq = 1L
    }
}

/**
 * 应用导航：底部三个标签页 + 压在其上的页面栈。
 * `Nav.go("settings")` 切换标签，`Nav.go("backup")` 打开一页，`Nav.back()` 返回。
 */
object Nav {
    const val EVENTS = "events"
    const val GROUPS = "groups"
    const val SETTINGS = "settings"
    val Tabs = listOf(EVENTS, GROUPS, SETTINGS)

    val tab = mutableStateOf(EVENTS)
    val stack = mutableStateListOf<Route>()
    internal val popRequests = mutableIntStateOf(0)

    /** 日程页的分组筛选：null=全部，""=未分组；分组页点进某个分组时设置。 */
    val groupFilter = mutableStateOf<String?>(null)

    fun go(key: String, arg: String = "") {
        if (key in Tabs) {
            stack.clear()
            tab.value = key
            return
        }
        val top = stack.lastOrNull()
        if (top != null && top.key == key && top.arg == arg) return
        stack.add(Route(key, arg))
    }

    /** 带动画地关闭顶层页面（顶栏返回键、保存后返回）。 */
    fun back() {
        if (stack.isNotEmpty()) popRequests.intValue++
    }
}

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 绘制根页面和栈顶两页：新页面从右侧滑入，下层页面略微视差左移并压暗；
 * 系统返回手势（预测性返回）可以跟手拖出页面，松手后决定返回或回弹。
 * 更深的页面只保留状态，不参与绘制。
 */
@Composable
fun StackHost(root: @Composable () -> Unit, page: @Composable (Route) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val stack = Nav.stack
    val scope = rememberCoroutineScope()
    // 0 = 页面就位，1 = 完全移出屏幕右侧；新页面从 1 开始。
    val anims = remember { HashMap<Long, Animatable<Float, AnimationVector1D>>() }
    fun anim(r: Route) = anims.getOrPut(r.id) { Animatable(1f) }

    // 页面出栈（返回或切换标签清栈）后，丢弃它的动画和保存状态。
    LaunchedEffect(Unit) {
        snapshotFlow { stack.map { it.id }.toSet() }.collect { live ->
            anims.keys.filter { it !in live }.forEach {
                anims.remove(it)
                holder.removeState(it)
            }
        }
    }
    LaunchedEffect(Nav.popRequests.intValue) {
        if (Nav.popRequests.intValue == 0) return@LaunchedEffect
        val r = stack.lastOrNull() ?: return@LaunchedEffect
        anim(r).animateTo(1f, tween(260, easing = Emphasized))
        stack.remove(r)
    }
    PredictiveBackHandler(enabled = stack.isNotEmpty()) { progress ->
        val r = stack.lastOrNull() ?: return@PredictiveBackHandler
        val a = anim(r)
        try {
            progress.collect { e -> a.snapTo(e.progress * 0.92f) }
            a.animateTo(1f, tween(200, easing = Emphasized))
            stack.remove(r)
        } catch (e: CancellationException) {
            scope.launch { a.animateTo(0f, tween(220, easing = Emphasized)) }
            throw e
        }
    }
    // 不在日程页时，返回先回到日程页，再由系统退到桌面。
    BackHandler(enabled = stack.isEmpty() && Nav.tab.value != Nav.EVENTS) { Nav.tab.value = Nav.EVENTS }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val n = stack.size
        val top = stack.lastOrNull()
        val topAnim = top?.let { anim(it) }
        val bg = MaterialTheme.colorScheme.background

        // 被上层页面盖住的一层：视差左移，完全盖住后不再绘制。
        fun Modifier.under(): Modifier = graphicsLayer {
            val p = topAnim?.value ?: 1f
            translationX = -0.25f * w * (1f - p)
            alpha = if (p <= 0.001f) 0f else 1f
        }

        if (n <= 1) {
            Box(Modifier.fillMaxSize().under()) {
                holder.SaveableStateProvider("root") { root() }
            }
        }
        stack.forEachIndexed { i, r ->
            if (i < n - 2) return@forEachIndexed
            key(r.id) {
                val isTop = i == n - 1
                val a = anim(r)
                if (isTop) {
                    LaunchedEffect(r.id) { a.animateTo(0f, tween(340, easing = Emphasized)) }
                    // 下层页面上的遮罩，同时吞掉落在下层的触摸。
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { alpha = (1f - a.value) }
                            .background(Color.Black.copy(alpha = 0.22f))
                            .pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                            }
                    )
                }
                Box(
                    Modifier.fillMaxSize()
                        .let { m ->
                            if (isTop) m.graphicsLayer {
                                translationX = w * a.value
                                shadowElevation = if (a.value > 0f) 24f else 0f
                                shape = RectangleShape
                            } else m.under()
                        }
                        .background(bg)
                ) {
                    holder.SaveableStateProvider(r.id) { page(r) }
                }
            }
        }
    }
}
