package com.smartsup.controller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorScheme = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    error = Color(0xFFB91C1C),
)

@Composable
fun SmartSupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content,
    )
}
