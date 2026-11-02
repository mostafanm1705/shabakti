package com.shabakti.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B3D5C)
val Mint = Color(0xFF14B88A)
val Danger = Color(0xFFD64545)
val Amber = Color(0xFFE09B1A)

private val Light = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Mint,
    onSecondary = Color.White,
    background = Color(0xFFF4F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF3),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FC4EA),
    onPrimary = Color(0xFF00283D),
    secondary = Mint,
    background = Color(0xFF0E151B),
    surface = Color(0xFF172129),
    surfaceVariant = Color(0xFF22303B),
    error = Color(0xFFFF7A7A),
)

@Composable
fun ShabaktiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
