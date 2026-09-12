package me.colinwatson.beerdebt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.ui.theme.Palette

/** The big gold capsule: + Beer, Cheers!, Connect. */
@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.gold, contentColor = Palette.ink, disabledContainerColor = Palette.gold.copy(alpha = 0.6f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small rounded status tag: PAID, Applied, Ignored. */
@Composable
fun Pill(text: String, color: Color = Palette.creditSoft) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A floating card on the forest background. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Palette.card, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForestTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, color = Palette.cream) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.cream) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/** Cream pill on a dark track, like the concept art. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Palette.cream, activeContentColor = Palette.ink,
                    inactiveContainerColor = Palette.forestDeep, inactiveContentColor = Palette.cream.copy(alpha = 0.85f),
                    activeBorderColor = Color.Transparent, inactiveBorderColor = Color.Transparent,
                ),
                icon = {},
            ) { Text(label(option), fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Palette.cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.cream.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun StatRow(vararg stats: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        stats.forEachIndexed { i, (value, label) ->
            Stat(value, label, Modifier.weight(1f))
            if (i < stats.lastIndex) Box(Modifier.background(Palette.cream.copy(alpha = 0.25f)).padding(vertical = 18.dp).padding(horizontal = 0.5.dp))
        }
    }
}
