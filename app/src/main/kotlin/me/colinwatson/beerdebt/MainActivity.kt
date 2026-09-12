package me.colinwatson.beerdebt

import android.app.Activity
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
}

/**
 * Debug builds only, for `scripts/screenshots.sh` (the iOS `-debugScreen` equivalent):
 * `am start -n me.colinwatson.beerdebt/.MainActivity --es debugScreen runs` opens a screen
 * directly. Values: debt | runs | settings | beerAdded | beerDetail | debtFree.
 */
object DebugLaunch {
    const val EXTRA = "debugScreen"

    fun screen(activity: Activity): String? {
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) activity.intent?.getStringExtra(EXTRA) else null
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val app = context.applicationContext as BeerDebtApp
    val prefs = remember { context.getSharedPreferences("app", 0) }
    var onboardingComplete by remember { mutableStateOf(prefs.getBoolean("onboardingComplete", false)) }
    val debugScreen = remember { (context as? Activity)?.let(DebugLaunch::screen) }
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val syncState by app.sync.state.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.sync.isAppActive = true
        scope.launch { app.sync.syncIfConnected(); app.sync.showPendingCelebration() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { app.sync.isAppActive = false }

    val start = when {
        !onboardingComplete -> Routes.ONBOARDING
        debugScreen == "debt" || debugScreen == "beerDetail" -> Routes.DEBT
        debugScreen == "runs" -> Routes.RUNS
        debugScreen == "settings" -> Routes.SETTINGS
        else -> Routes.HOME
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
                showLatestBeerSheet = debugScreen == "beerAdded",
            )
        }
        composable(Routes.DEBT) { DebtScreen(onBack = { nav.popBackStack() }, openFirstBeer = debugScreen == "beerDetail") }
        composable(Routes.RUNS) { RunsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }

    var sampleParty by remember {
        mutableStateOf(if (debugScreen == "debtFree") DebtFreeCelebration(totalBeers = 4, milesRepaid = 4.6, creditBeers = 0.4) else null)
    }
    sampleParty?.let { party -> DebtFreeSheet(party, onDismiss = { sampleParty = null }) }
    syncState.celebration?.let { party ->
        DebtFreeSheet(party, onDismiss = { app.sync.clearCelebration() })
    }
}
