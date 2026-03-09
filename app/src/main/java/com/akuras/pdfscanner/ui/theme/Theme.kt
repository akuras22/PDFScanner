package com.akuras.pdfscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = TealBlue,
    onPrimary = Cream,
    secondary = Mint,
    background = Cream,
    surface = Cream,
    onSurface = Slate
)

private val DarkColors = darkColorScheme(
    primary = SkyBlue,
    secondary = Mint
)

@Composable
fun PDFScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
