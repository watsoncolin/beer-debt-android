package me.colinwatson.beerdebt.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import me.colinwatson.beerdebt.BeerDebtApp
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.theme.Backdrop
import me.colinwatson.beerdebt.ui.theme.Palette

/** First run: explain the one permission, ask for it, allow "not now". */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BeerDebtApp
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { app.sync.connected(); connecting = false; onDone() }
    }

    Backdrop {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(120.dp))
            Spacer(Modifier.height(12.dp))
            Text("Beer Debt", color = Palette.cream, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            Text("Drink now. Run later.", color = Palette.cream.copy(alpha = 0.7f), fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().background(Palette.forestDeep.copy(alpha = 0.85f), RoundedCornerShape(18.dp)).padding(20.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                Rule(Icons.Default.LocalBar, "Every beer costs a mile.", "Tap + Beer and it goes on your tab.")
                Rule(Icons.Default.DirectionsRun, "Runs pay the tab.", "Health Connect hands over your runs. Only running counts. We'll ping you when one lands.")
                Rule(Icons.Default.Percent, "Ignore it and it grows.", "Interest starts after 24 hours. The rules are yours to tune.")
            }
            Spacer(Modifier.weight(1f))
            GoldButton(if (connecting) "Connecting…" else "Connect Health Connect", enabled = !connecting && app.sync.isAvailable) {
                connecting = true
                launcher.launch(app.sync.health.permissions)
            }
            if (app.sync.health.needsInstall) {
                TextButton(onClick = { context.startActivity(app.sync.health.installIntent()) }) { Text("Install Health Connect from Google Play", color = Palette.gold) }
            } else if (!app.sync.isAvailable) {
                Text("Health Connect isn't available on this device.", color = Palette.cream.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            TextButton(onClick = onDone) { Text("Not now", color = Palette.cream.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
private fun Rule(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Palette.gold, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Palette.cream, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(detail, color = Palette.cream.copy(alpha = 0.7f), fontSize = 15.sp)
        }
    }
}
