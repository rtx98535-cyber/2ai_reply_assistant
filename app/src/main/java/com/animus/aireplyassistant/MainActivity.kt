package com.animus.aireplyassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.animus.aireplyassistant.accessibility.ReplyAccessibilityService
import com.animus.aireplyassistant.core.InstallIdStore
import com.animus.aireplyassistant.core.isAccessibilityServiceEnabled
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AIReplyAssistantTheme { Home() }
        }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val installId = InstallIdStore.getOrCreateInstallId(context)
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(false) }

    fun refresh() {
        enabled = isAccessibilityServiceEnabled(
            context,
            ComponentName(context, ReplyAccessibilityService::class.java),
        )
    }

    // Keep the status fresh after returning from Settings.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI Reply Assistant (v1 slice)", style = MaterialTheme.typography.titleLarge)
        Text("Install ID: $installId", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Accessibility service", style = MaterialTheme.typography.bodyLarge)
            Text(if (enabled) "Enabled" else "Disabled")
        }

        Text(
            "Enable the Accessibility Service. On X, tap AI+ then GPT Reply to auto-send post context and screenshot to ChatGPT and get 3 reply cards.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Accessibility Settings")
        }
    }
}
