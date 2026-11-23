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

/** Tapping a new-mail notification asks the app to show the list of all mails, wherever the user left off. */
object NavSignal {
    val goHome = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile var handled = 0
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(com.foldmail.app.data.Notifier.EXTRA_FROM_NOTIFICATION, false)) NavSignal.goHome.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings.get(this)
        MailSyncWorker.schedule(this, settings.notifications.value)
        // Android 13+ shows no notifications until the user allows them; ask once at start.
        if (settings.notifications.value && android.os.Build.VERSION.SDK_INT >= 33 && !com.foldmail.app.data.Notifier.hasPermission(this)) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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
