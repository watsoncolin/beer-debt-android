package me.colinwatson.beerdebt

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.colinwatson.beerdebt.health.DebtFreeCelebration
import me.colinwatson.beerdebt.ui.debt.DebtScreen
import me.colinwatson.beerdebt.ui.home.DebtFreeSheet
import me.colinwatson.beerdebt.ui.home.HomeScreen
import me.colinwatson.beerdebt.ui.onboarding.OnboardingScreen
import me.colinwatson.beerdebt.ui.privacy.PrivacyScreen
import me.colinwatson.beerdebt.ui.streak.StreakActivatedSheet
import me.colinwatson.beerdebt.ui.streak.FreezeEarnedSheet
import me.colinwatson.beerdebt.ui.streak.StreakScreen
import me.colinwatson.beerdebt.health.StreakCelebration
import me.colinwatson.beerdebt.health.FreezeEarnedCelebration
import me.colinwatson.beerdebt.widget.BalanceWidgetReceiver
import androidx.compose.runtime.LaunchedEffect
import me.colinwatson.beerdebt.ui.runs.RunsScreen
import me.colinwatson.beerdebt.ui.settings.SettingsScreen
import me.colinwatson.beerdebt.ui.theme.BeerDebtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BeerDebtTheme { Root() } }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DEBT = "debt"
    const val RUNS = "runs"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val STREAK = "streak"
}

/**
 * Debug builds only, for `scripts/screenshots.sh` (the iOS `-debugScreen` equivalent):
 * `am start -n me.colinwatson.beerdebt/.MainActivity --es debugScreen runs` opens a screen
 * directly. Values: debt | runs | settings | privacy | streak | beerAdded | beerDetail | debtFree | streakActivated | widget
 * (widget asks the launcher to pin the home screen widget).
 */
object DebugLaunch {
    const val EXTRA = "debugScreen"

    fun screen(activity: Activity): String? {
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) activity.intent?.getStringExtra(EXTRA) else null
    }
}

/** Health Connect sends these when the user asks why we want access; both land on the Privacy screen. */
private val rationaleActions = setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE")

@Composable
private fun Root() {
    val context = LocalContext.current
    val app = context.applicationContext as BeerDebtApp
    val prefs = remember { context.getSharedPreferences("app", 0) }
    var onboardingComplete by remember { mutableStateOf(prefs.getBoolean("onboardingComplete", false)) }
    val debugScreen = remember { (context as? Activity)?.let(DebugLaunch::screen) }
    val rationale = remember { (context as? Activity)?.intent?.action in rationaleActions }
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val syncState by app.sync.state.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.sync.isAppActive = true
        scope.launch { app.sync.syncIfConnected(); app.sync.showPendingCelebration() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { app.sync.isAppActive = false }

    val start = when {
        rationale || debugScreen == "privacy" -> Routes.PRIVACY
        !onboardingComplete -> Routes.ONBOARDING
        debugScreen == "debt" || debugScreen == "beerDetail" || debugScreen == "paid" -> Routes.DEBT
        debugScreen == "runs" -> Routes.RUNS
        debugScreen == "settings" -> Routes.SETTINGS
        debugScreen == "streak" -> Routes.STREAK
        else -> Routes.HOME
    }
    if (debugScreen == "widget") LaunchedEffect(Unit) {
        AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, BalanceWidgetReceiver::class.java), null, null)
    }
    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                prefs.edit().putBoolean("onboardingComplete", true).apply()
                onboardingComplete = true
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenDebt = { nav.navigate(Routes.DEBT) },
                onOpenRuns = { nav.navigate(Routes.RUNS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenStreak = { nav.navigate(Routes.STREAK) },
                showLatestBeerSheet = debugScreen == "beerAdded",
            )
        }
        composable(Routes.DEBT) {
            DebtScreen(
                onBack = { nav.popBackStack() },
                openFirstBeer = debugScreen == "beerDetail",
                showPaid = debugScreen == "paid",
            )
        }
        composable(Routes.RUNS) { RunsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onOpenPrivacy = { nav.navigate(Routes.PRIVACY) }) }
        composable(Routes.STREAK) { StreakScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { if (!nav.popBackStack()) (context as? Activity)?.finish() }) }
    }

    var sampleParty by remember {
        mutableStateOf(if (debugScreen == "debtFree") DebtFreeCelebration(totalBeers = 4, milesRepaid = 4.6, creditBeers = 0.4) else null)
    }
    sampleParty?.let { party -> DebtFreeSheet(party, onDismiss = { sampleParty = null }) }
    var sampleStreak by remember {
        mutableStateOf(if (debugScreen == "streakActivated") StreakCelebration(maxOf(2, app.store.report().streak.currentStreakDays)) else null)
    }
    sampleStreak?.let { party -> StreakActivatedSheet(party, onDismiss = { sampleStreak = null }) }
    syncState.celebration?.let { party ->
        DebtFreeSheet(party, onDismiss = { app.sync.clearCelebration() })
    }
    if (syncState.celebration == null) syncState.streakCelebration?.let { party ->
        StreakActivatedSheet(party, onDismiss = { app.sync.clearStreakCelebration() })
    }
    var sampleFreeze by remember {
        mutableStateOf(if (debugScreen == "freezeEarned") FreezeEarnedCelebration(maxOf(5, app.store.report().streak.currentStreakDays)) else null)
    }
    sampleFreeze?.let { party -> FreezeEarnedSheet(party, onDismiss = { sampleFreeze = null }) }
    if (syncState.celebration == null && syncState.streakCelebration == null) syncState.freezeEarned?.let { party ->
        FreezeEarnedSheet(party, onDismiss = { app.sync.clearFreezeEarned() })
    }
}
