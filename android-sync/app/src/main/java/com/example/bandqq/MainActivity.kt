package com.example.bandqq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.ui.BandQQApp
import com.example.bandqq.ui.BandQQTheme
import com.example.bandqq.ui.LocalEnableBlur
import com.example.bandqq.ui.LocalEnableFloatingBottomBar
import com.example.bandqq.ui.LocalEnableFloatingBottomBarGlass
import com.example.bandqq.ui.LocalEnableNavigationBadge
import com.example.bandqq.ui.LocalMotionSpeed
import com.example.bandqq.ui.LocalMotionStagger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：与 KernelSU 一致，状态栏/导航栏透明，内容延伸至系统栏之后
        enableEdgeToEdge()
        // 温启动时重新应用预测性返回开关（设置切换后无需冷启动进程）
        (application as? BandQQApplication)?.applyPredictiveBackFlag()
        val configManager = ConfigManager(applicationContext)
        setContent {
            // DataStore Flow 直接驱动全局主题：设置页切换立即生效，无需重启
            val themeMode by configManager.observeThemeMode().collectAsState(initial = 0)
            val keyColor by configManager.observeKeyColor().collectAsState(initial = 0)
            val enableBlur by configManager.observeEnableBlur().collectAsState(initial = true)
            val floatingBar by configManager.observeFloatingBottomBar().collectAsState(initial = true)
            val navGlass by configManager.observeNavGlass().collectAsState(initial = true)
            val navBadge by configManager.observeNavigationBadge().collectAsState(initial = true)
            val pageScale by configManager.observePageScale().collectAsState(initial = 1.0f)
            val motionSpeed by configManager.observeMotionSpeed().collectAsState(initial = 1.0f)
            val motionStagger by configManager.observeMotionStagger().collectAsState(initial = 100)

            BandQQTheme(themeMode = themeMode, keyColor = keyColor, pageScale = pageScale) {
                // 对齐 KernelSU 的 CompositionLocal 注入方式：底栏/顶栏组件按需读取
                CompositionLocalProvider(
                    LocalEnableBlur provides enableBlur,
                    LocalEnableFloatingBottomBar provides floatingBar,
                    LocalEnableFloatingBottomBarGlass provides navGlass,
                    LocalEnableNavigationBadge provides navBadge,
                    LocalMotionSpeed provides motionSpeed,
                    LocalMotionStagger provides motionStagger,
                ) {
                    BandQQApp()
                }
            }
            BluetoothPermissionRequester()
        }
    }
}

@Composable
private fun BluetoothPermissionRequester() {
    val context = LocalContext.current
    // ⚠️ 蓝牙/通知必须用各自的 launcher 且串行请求：同一 launcher 连续 launch
    // 会取消前一个请求，导致其中一个永远弹不出来（v2.4.5 修复）
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 蓝牙请求完成后接着请求通知权限（Android 13+ 前台服务通知依赖它）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
