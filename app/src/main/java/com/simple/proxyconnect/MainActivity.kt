package com.simple.proxyconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.simple.proxyconnect.ui.theme.SimpleProxyTheme
import com.simple.proxyconnect.ui.navigation.AppNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleProxyTheme {
                AppNavigation()
            }
        }
    }
}
