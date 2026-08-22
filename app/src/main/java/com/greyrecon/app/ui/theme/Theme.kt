package com.greyrecon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreyReconDark = darkColorScheme(
    primary = Color(0xFF7DD3C0),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF121417),
    surface = Color(0xFF1B1E22),
)

private val GreyReconLight = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF52606D),
)

@Composable
fun GreyReconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) GreyReconDark else GreyReconLight,
        content = content,
    )
}
