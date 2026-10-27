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

// TV Optimizer Pro: forest-green and antique-gold brand.
// Dynamic color is opt-in so a device's wallpaper never silently overrides the brand.
private val LightBrand = lightColorScheme(
    primary = Color(0xFF1A5842),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E9D9),
    onPrimaryContainer = Color(0xFF103C2B),
    secondary = Color(0xFF84631F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E3BC),
    onSecondaryContainer = Color(0xFF33270E),
    tertiary = Color(0xFF476B58),
    tertiaryContainer = Color(0xFFDEECE1),
    error = Color(0xFFB4232B),
    background = Color(0xFFF5F8F3),
    surface = Color(0xFFF5F8F3),
    surfaceContainerLow = Color(0xFFF0F4EE),
    surfaceContainer = Color(0xFFE8EFE7),
    surfaceContainerHigh = Color(0xFFE0EAE0),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFE2BE71),
    onPrimary = Color(0xFF2B260D),
    primaryContainer = Color(0xFF314A35),
    onPrimaryContainer = Color(0xFFF3E6C8),
    secondary = Color(0xFF9BC6AD),
    onSecondary = Color(0xFF103527),
    secondaryContainer = Color(0xFF244E3B),
    onSecondaryContainer = Color(0xFFD8EBDD),
    tertiary = Color(0xFFD1AC63),
    tertiaryContainer = Color(0xFF574821),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0D1D16),
    surface = Color(0xFF0D1D16),
    surfaceContainerLow = Color(0xFF13271D),
    surfaceContainer = Color(0xFF193023),
    surfaceContainerHigh = Color(0xFF254333),
)

/** Tabular figures keep real-time measurements aligned. */
val StatNumber = TextStyle(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
val SmallNumber = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TellyscopeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = false,
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
