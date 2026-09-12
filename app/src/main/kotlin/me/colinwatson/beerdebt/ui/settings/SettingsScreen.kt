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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import me.colinwatson.beerdebt.notify.WeeklySummary
import me.colinwatson.beerdebt.ui.Format
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette

/** Tune the rules; every change is a forward-only rules event. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrivacy: () -> Unit = {}) {
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
    val weekly = app.sync.weekly
    var weeklyEnabled by remember { mutableStateOf(weekly.enabled) }
    var weeklyDenied by remember { mutableStateOf(false) }
    var weeklyWeekday by remember { mutableStateOf(weekly.weekday) }
    var weeklyHour by remember { mutableStateOf(weekly.hour) }
    var weeklyMinute by remember { mutableStateOf(weekly.minute) }
    var pickingTime by remember { mutableStateOf(false) }
    var reopeningBooks by remember { mutableStateOf(false) }
    val weeklyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        weekly.setEnabled(granted); weeklyEnabled = granted; weeklyDenied = !granted
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
                            if (app.sync.health.needsInstall) {
                                ValueRow("Health Connect", "Not installed")
                                HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                                Text("Install Health Connect", color = Palette.gold, modifier = Modifier.fillMaxWidth().clickable { context.startActivity(app.sync.health.installIntent()) }.padding(vertical = 12.dp))
                            } else if (!app.sync.isAvailable) {
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

                item { SectionHeader("Weekly Summary") }
                item {
                    Card {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Weekly summary", color = Palette.cream, modifier = Modifier.weight(1f))
                                Switch(checked = weeklyEnabled, onCheckedChange = { on ->
                                    if (on && Build.VERSION.SDK_INT >= 33 && !app.notifier.canPost()) weeklyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else { weekly.setEnabled(on); weeklyEnabled = on; weeklyDenied = false }
                                }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.gold, checkedThumbColor = Palette.ink))
                            }
                            if (weeklyEnabled) {
                                HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                                PickerRow("Day", (1..7).toList(), weeklyWeekday, { WeeklySummary.weekdayNames[it - 1] }) { d -> weekly.weekday = d; weeklyWeekday = d }
                                Row(Modifier.fillMaxWidth().clickable { pickingTime = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Time", color = Palette.cream, modifier = Modifier.weight(1f))
                                    Text(Format.clock(weeklyHour, weeklyMinute), color = Palette.gold)
                                }
                            }
                            if (weeklyDenied) {
                                Text("Notifications are off for Beer Debt. Turn them on in Settings › Apps › Beer Debt › Notifications.", color = Palette.debt, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                item { Footer("A once-a-week recap: beers, miles, and where your tab stands.") }

                item { SectionHeader("About") }
                item {
                    Card {
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { reopeningBooks = true }.padding(vertical = 12.dp)) {
                                Text("Books opened", color = Palette.cream, modifier = Modifier.weight(1f))
                                Text(Format.dateTime(ledger.booksOpenedAt), color = Palette.gold)
                            }
                            HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                            ValueRow("Version", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?")
                            HorizontalDivider(color = Palette.cream.copy(alpha = 0.1f))
                            Text("Privacy Policy", color = Palette.gold, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPrivacy).padding(vertical = 12.dp))
                        }
                    }
                }
                item { Footer("Runs that ended before the books opened don't count. Tap the date to open the books earlier and pull those runs in from Health Connect.") }
            }
        }
    }

    if (reopeningBooks) {
        ReopenBooksPicker(current = ledger.booksOpenedAt, onPick = { scope.launch { app.sync.reopenBooks(it) }; reopeningBooks = false }, onDismiss = { reopeningBooks = false })
    }
    if (pickingTime) {
        WeeklyTimePicker(weeklyHour, weeklyMinute, onPick = { h, m -> weekly.hour = h; weekly.minute = m; weeklyHour = h; weeklyMinute = m; pickingTime = false }, onDismiss = { pickingTime = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklyTimePicker(hour: Int, minute: Int, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
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

/** Pick an earlier opening for the books: the whole picked day counts. Earlier only, a year at most. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReopenBooksPicker(current: java.time.Instant, onPick: (java.time.Instant) -> Unit, onDismiss: () -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    val utc = java.time.ZoneId.of("UTC")
    val currentDay = current.atZone(zone).toLocalDate()
    val earliestDay = java.time.Instant.now().minusSeconds(me.colinwatson.beerdebt.data.LedgerStore.REOPENING_WINDOW_SECONDS).atZone(zone).toLocalDate()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = currentDay.atStartOfDay(utc).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(utc).toLocalDate()
                return !d.isAfter(currentDay) && !d.isBefore(earliestDay)
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                val day = java.time.Instant.ofEpochMilli(millis).atZone(utc).toLocalDate()
                onPick(day.atStartOfDay(zone).toInstant())
            }) { Text("Move back") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state, title = { Text("Open the books earlier", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}
