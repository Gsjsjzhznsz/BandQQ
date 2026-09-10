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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.ui.BandQQApp
import com.example.bandqq.ui.BandQQTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：与 KernelSU 一致，状态栏/导航栏透明，内容延伸至系统栏之后
        enableEdgeToEdge()
        val configManager = ConfigManager(applicationContext)
        setContent {
            // DataStore Flow 直接驱动全局主题：设置页切换立即生效，无需重启
            val themeMode by configManager.observeThemeMode().collectAsState(initial = 0)
            val navGlass by configManager.observeNavGlass().collectAsState(initial = true)
            BandQQTheme(themeMode) { BandQQApp(navGlass = navGlass) }
            BluetoothPermissionRequester()
        }
    }
}

@Composable
private fun BluetoothPermissionRequester() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}
