package io.github.fgozxy.await

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.fgozxy.await.sync.SyncCoordinator
import io.github.fgozxy.await.ui.HomeScreen
import io.github.fgozxy.await.ui.theme.AwaitTheme
import io.github.fgozxy.await.vm.EventViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = EventViewModel(application)
        setContent { AwaitTheme { HomeScreen(viewModel = viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        SyncCoordinator.start(this)
    }
}
