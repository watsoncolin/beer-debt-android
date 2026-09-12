package me.colinwatson.beerdebt.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.engine.CompoundingPeriod
import me.colinwatson.beerdebt.engine.Rules
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette

/** Tune the rules; every change is a forward-only rules event. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BeerDebtApp
    val ledger by app.store.ledger.collectAsState()
    val sync by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rules = ledger.currentRules
    fun update(transform: (Rules) -> Rules) = app.store.updateRules(transform(rules))

    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { app.sync.connected() }
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.sync.setRunNotifications(granted)
    }

    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar("Settings", onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionHeader("The Rules") }
                item {
                    Card {
                        Column {
                            PickerRow("Miles per Beer", listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0).withCurrent(rules.milesPerBeer), rules.milesPerBeer, { Format.miles(it) }) { v -> update { it.copy(milesPerBeer = v) } }
                            PickerRow("Interest Rate", listOf(0.0, 0.05, 0.10, 0.15, 0.20, 0.25).withCurrent(rules.interestRate), rules.interestRate, { if (it == 0.0) "Off" else Format.percent(it) }) { v -> update { it.copy(interestRate = v) } }
                            PickerRow("Interest Frequency", CompoundingPeriod.entries, rules.interestPeriod, { if (it == CompoundingPeriod.DAILY) "Daily" else "Weekly" }) { v -> update { it.copy(interestPeriod = v) } }
                            PickerRow("Grace Period", listOf(0.0, 12 * 3600.0, 24 * 3600.0, 48 * 3600.0, 7 * 86_400.0).withCurrent(rules.gracePeriod), rules.gracePeriod, { Format.gracePeriod(it) }) { v -> update { it.copy(gracePeriod = v) } }
                            PickerRow("Maximum Credit", (1..10).map { it.toDouble() }.withCurrent(rules.maximumCreditBeers), rules.maximumCreditBeers, { Format.beersLabel(it) }) { v -> update { it.copy(maximumCreditBeers = v) } }
                            PickerRow("Credit Decay", listOf(0.0, 0.05, 0.10, 0.20, 0.25).withCurrent(rules.creditDecayRatePerWeek), rules.creditDecayRatePerWeek, { if (it == 0.0) "Off" else "${Format.percent(it)} / week" }, last = true) { v -> update { it.copy(creditDecayRatePerWeek = v) } }
                        }
                    }
                }
                item { Footer("Changes apply from now on. Past interest and paid beers are never recalculated, so the economy doesn't shift under you.") }

                item { SectionHeader("Health & Data") }
                item {
                    Card {
                        Column {
                            if (!app.sync.isAvailable) {
                                Text("Health Connect isn't available on this device.", color = Palette.cream.copy(alpha = 0.7f))
                            } else if (sync.isConnected) {
                                ValueRow("Health Connect", "Connected")
                                HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                                Text(if (sync.isSyncing) "Syncing…" else "Sync now", color = Palette.gold, modifier = Modifier.fillMaxWidth().clickable { scope.launch { app.sync.sync() } }.padding(vertical = 12.dp))
                                sync.lastSyncAt?.let { HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f)); ValueRow("Last sync", Format.dateTime(it)) }
                                HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Notify me when a run lands", color = Palette.cream, modifier = Modifier.weight(1f))
                                    Switch(checked = sync.runNotificationsEnabled, onCheckedChange = { on ->
                                        if (on && Build.VERSION.SDK_INT >= 33) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else app.sync.setRunNotifications(on)
                                    }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.gold, checkedThumbColor = Palette.ink))
                                }
                            } else {
                                ValueRow("Health Connect", "Not connected")
                                HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                                Text("Connect Health Connect", color = Palette.gold, modifier = Modifier.fillMaxWidth().clickable { healthLauncher.launch(app.sync.health.permissions) }.padding(vertical = 12.dp))
                            }
                            sync.lastError?.let { Text(it, color = Palette.debt, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                        }
                    }
                }
                item { Footer("Only running workouts count toward your balance. Runs sync when you open the app and about hourly in the background.") }

                item { SectionHeader("About") }
                item {
                    Card {
                        Column {
                            ValueRow("Books opened", Format.dateTime(ledger.booksOpenedAt))
                            HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                            ValueRow("Version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?")
                        }
                    }
                }
            }
        }
    }
}

private fun <T : Comparable<T>> List<T>.withCurrent(current: T): List<T> = (this + current).distinct().sorted()

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Palette.cream.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
private fun Footer(text: String) {
    Text(text, color = Palette.cream.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, color = Palette.cream, modifier = Modifier.weight(1f))
        Text(value, color = Palette.cream.copy(alpha = 0.7f))
    }
}

@Composable
private fun <T> PickerRow(label: String, options: List<T>, selected: T, text: (T) -> String, last: Boolean = false, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.cream, modifier = Modifier.weight(1f))
        Text(text(selected), color = Palette.gold)
        Spacer(Modifier.width(4.dp))
        Text("⌃⌄", color = Palette.gold, fontSize = 11.sp)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { onSelect(option); open = false }) }
        }
    }
    if (!last) HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
}
