package com.animus.aireplyassistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()

@Composable
fun AIReplyAssistantTheme(
    useSurface: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = LightColors) {
        if (useSurface) {
            Surface(color = MaterialTheme.colorScheme.background) {
                content()
            }
        } else {
            content()
        }
    }
}
