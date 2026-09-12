package me.colinwatson.beerdebt.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import me.colinwatson.beerdebt.R

/** The iOS palette (Theme.swift): forest, gold, cream. Everything is dark. */
object Palette {
    val gold = Color(0xFFF5BA42)
    val forest = Color(0xFF1E2E2A)
    val forestDeep = Color(0xFF14201D)
    val cream = Color(0xFFF6F0E4)
    val ink = Color(0xFF1C1C19)
    val debt = Color(0xFFEF6C51)
    val credit = Color(0xFF5CB878)
    val creditSoft = Color(0xFF9CD8A9)
    val card = Color(0xFF2A3C37)
}

private val scheme = darkColorScheme(
    primary = Palette.gold,
    onPrimary = Palette.ink,
    secondary = Palette.credit,
    background = Palette.forest,
    onBackground = Palette.cream,
    surface = Palette.forest,
    onSurface = Palette.cream,
    surfaceVariant = Palette.card,
    onSurfaceVariant = Palette.cream.copy(alpha = 0.7f),
    surfaceContainer = Palette.card,
    surfaceContainerHigh = Palette.card,
    surfaceContainerHighest = Palette.card,
    surfaceContainerLow = Palette.forestDeep,
    error = Palette.debt,
)

@Composable
fun BeerDebtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Forest gradient under every secondary screen. */
@Composable
fun ForestBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.forest, Palette.forestDeep)))
    ) { content() }
}

/** The painted trail-and-mountains scene under a scrim, like iOS `Backdrop`. */
@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.forest)) {
        Image(
            painter = painterResource(R.drawable.home_backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Palette.forestDeep.copy(alpha = 0.55f),
                    0.30f to Palette.forestDeep.copy(alpha = 0.30f),
                    0.55f to Palette.forestDeep.copy(alpha = 0.45f),
                    1f to Palette.forestDeep.copy(alpha = 0.88f),
                )
            )
        )
        content()
    }
}
