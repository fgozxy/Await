package io.github.fgozxy.await

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.fgozxy.await.sync.SyncCoordinator
import io.github.fgozxy.await.ui.AwaitRoot
import io.github.fgozxy.await.ui.theme.AwaitTheme
import io.github.fgozxy.await.vm.EventViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: EventViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // 导航栏完全透明，由底部导航栏或页面自己延伸到系统手势条/三键区域下方
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { AwaitTheme { AwaitRoot(viewModel = viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        SyncCoordinator.start(this)
    }
}
