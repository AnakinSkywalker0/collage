package com.abhishek.collage.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * One fixed light scheme with heavily rounded shapes.
 *
 * Deliberately NOT Material You dynamic colour, which this app shipped with by
 * default. Dynamic colour repaints the UI from the user's wallpaper, so the app
 * looked different in every screenshot and clashed with the collage it produces
 * -- including in the submitted screen recording. It is also not tied to the
 * system light/dark setting, for the same reason: the output image is a fixed
 * design, and the app around it should be too.
 */
private val CollageScheme = lightColorScheme(
    primary = Rose,
    onPrimary = Card,
    primaryContainer = RoseSoft,
    onPrimaryContainer = Ink,
    secondary = Teal,
    onSecondary = Card,
    tertiary = Amber,
    onTertiary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = InkSoft,
    outline = InkSoft,
    outlineVariant = CreamDeep
)

/** Chunky radii throughout -- the blocks are the design, so they carry it. */
private val RoundedShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun CollageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CollageScheme,
        shapes = RoundedShapes,
        typography = Typography,
        content = content
    )
}
