package io.angra.salvage

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One dark instrument-panel palette. This is a forensic tool, so surfaces stay near black and
 * colour carries meaning: jade for what is recoverable, amber for partial or unverified, rust
 * for failure.
 */
val Ink = Color(0xFF06080B)
val Surface1 = Color(0xFF0C1116)
val Surface2 = Color(0xFF131A21)
val Hairline = Color(0xFF1E2932)
val Jade = Color(0xFF3DFFA8)
val JadeDim = Color(0xFF1C5B41)
val Amber = Color(0xFFFFC24B)
val Rust = Color(0xFFFF6B5A)
val Bone = Color(0xFFE3ECF3)
val Muted = Color(0xFF7C8FA0)

val Mono = FontFamily.Monospace

/**
 * Sizes, paths and counts are monospace; prose is not. Setting everything in mono is what made
 * the old screen read as a terminal costume rather than an instrument.
 */
private val AngraType = Typography(
    headlineLarge = TextStyle(
        fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(
        fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp
    )
)

private val AngraColors = darkColorScheme(
    primary = Jade,
    onPrimary = Ink,
    primaryContainer = JadeDim,
    onPrimaryContainer = Jade,
    secondary = Amber,
    error = Rust,
    background = Ink,
    onBackground = Bone,
    surface = Surface1,
    onSurface = Bone,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Hairline
)

/**
 * Deliberately dark-only. A light variant would wash out the thumbnails this app exists to
 * show, so the identity commits rather than degrading in both directions.
 */
@Composable
fun AngraTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = AngraColors, typography = AngraType, content = content)

val CardRadius = 14.dp
val Gutter = 20.dp
