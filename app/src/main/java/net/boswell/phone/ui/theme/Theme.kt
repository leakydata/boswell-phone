package net.boswell.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material You: the phone's own wallpaper colors, light or dark with the system. */
@Composable
fun BoswellTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    val base = Typography()
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/**
 * A stable color per voice. Named people keep theirs everywhere; the palette
 * is chosen to stay readable as a bubble tint in light and dark themes.
 */
object Voices {
    private val palette = listOf(
        Color(0xFF4E79A7), Color(0xFFE15759), Color(0xFF59A14F), Color(0xFFB07AA1), Color(0xFFF28E2B),
        Color(0xFF76B7B2), Color(0xFFEDC948), Color(0xFFFF9DA7), Color(0xFF9C755F), Color(0xFF8CD17D),
    )

    /** Boswell's own voice: a neutral slate, so it never looks like one of the people. */
    val boswell = Color(0xFF6B7785)

    fun color(key: String?): Color = when (key) {
        null -> Color.Gray
        net.boswell.phone.process.BoswellLines.KEY -> boswell
        else -> palette[Math.floorMod(key.hashCode(), palette.size)]
    }

    fun initials(name: String): String = name.split(" ", "-").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    val small = TextStyle(fontSize = 12.sp)
}
