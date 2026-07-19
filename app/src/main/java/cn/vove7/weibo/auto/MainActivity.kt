package cn.vove7.weibo.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import cn.vove7.weibo.auto.ui.dashboard.DashboardScreen
import cn.vove7.weibo.auto.ui.dashboard.DashboardViewModel
import cn.vove7.weibo.auto.ui.theme.WeiboAutoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.factory(application as WeiboApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeiboAutoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibilityState()
    }
}
