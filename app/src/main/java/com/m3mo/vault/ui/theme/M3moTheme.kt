package com.m3mo.vault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val M3moPrimary = Color(0xFF1B6B5A)
private val M3moOnPrimary = Color(0xFFFFFFFF)
private val M3moPrimaryContainer = Color(0xFFB0F1DD)
private val M3moSecondary = Color(0xFF4A6360)
private val M3moTertiary = Color(0xFF436278)
private val M3moError = Color(0xFFBA1A1A)

private val M3moLightScheme = lightColorScheme(
    primary = M3moPrimary,
    onPrimary = M3moOnPrimary,
    primaryContainer = M3moPrimaryContainer,
    secondary = M3moSecondary,
    tertiary = M3moTertiary,
    error = M3moError,
)

private val M3moDarkScheme = darkColorScheme(
    primary = Color(0xFF95D5C2),
    onPrimary = Color(0xFF003B2E),
    primaryContainer = Color(0xFF005143),
    secondary = Color(0xFFB2CCC7),
    tertiary = Color(0xFFAACBE3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun M3moTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> M3moDarkScheme
        else -> M3moLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
