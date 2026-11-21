package com.foldmail.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.foldmail.app.data.AppSettings
import com.foldmail.app.data.MailSyncWorker
import com.foldmail.app.ui.FoldMailApp
import com.foldmail.designsystem.FoldMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings.get(this)
        MailSyncWorker.schedule(this, settings.notifications.value)
        setContent {
            val dark by settings.dark.collectAsState()
            DisposableEffect(dark) {
                val bar = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                onDispose { }
            }
            FoldMailTheme(darkTheme = dark) {
                FoldMailApp()
            }
        }
    }
}
