// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Branded fallback for phones without dynamic color (Android 11 and older).
// Seed: a deep teal, picked to read as "screen glow" without clashing with app icon tiles.
private val LightBrand = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E2D3),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    secondaryContainer = Color(0xFFE9E2D3),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E4FF),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF4FBFA),
    surface = Color(0xFFF4FBFA),
    surfaceContainerLow = Color(0xFFEEF5F4),
    surfaceContainer = Color(0xFFE9EFEE),
    surfaceContainerHigh = Color(0xFFE3E9E9),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFC4A765),
    onPrimary = Color(0xFF102C26),
    primaryContainer = Color(0xFF22483C),
    onPrimaryContainer = Color(0xFF9CF1F2),
    secondary = Color(0xFFD4C49C),
    secondaryContainer = Color(0xFF22483C),
    onSecondaryContainer = Color(0xFFCCE8E8),
    tertiary = Color(0xFFD7C78B),
    tertiaryContainer = Color(0xFF244435),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF102C26),
    surface = Color(0xFF0E1415),
    surfaceContainerLow = Color(0xFF15342B),
    surfaceContainer = Color(0xFF1C3A30),
    surfaceContainerHigh = Color(0xFF27473B),
)

/** Numbers come first: the app font with tabular figures, so values line up and don't jitter as they update. */
val StatNumber = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
val SmallNumber = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TellyscopeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkBrand
        else -> LightBrand
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        content = content,
    )
}
