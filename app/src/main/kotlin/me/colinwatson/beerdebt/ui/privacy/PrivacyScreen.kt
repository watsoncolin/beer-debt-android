package me.colinwatson.beerdebt.ui.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.colinwatson.beerdebt.ui.components.Card
import me.colinwatson.beerdebt.ui.components.ForestTopBar
import me.colinwatson.beerdebt.ui.components.GoldButton
import me.colinwatson.beerdebt.ui.theme.ForestBackground
import me.colinwatson.beerdebt.ui.theme.Palette

const val PRIVACY_POLICY_URL = "https://watsoncolin.github.io/beer-debt-ios/privacy.html"

/**
 * Why the app reads Health Connect, in plain words. Health Connect opens this
 * from its permission screen (ACTION_SHOW_PERMISSIONS_RATIONALE) and from
 * "Manage app" on Android 14+ (VIEW_PERMISSION_USAGE); Settings links it too.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ForestBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { ForestTopBar("Privacy", onBack) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Why Beer Debt reads Health Connect", color = Palette.cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Bullet("It reads your running workouts: when a run started and ended, and how far you went. Nothing else.")
                            Bullet("Runs pay down the beers on your tab and bank credit. That is the only thing the data is used for.")
                            Bullet("Everything stays on this phone. Nothing is uploaded, shared, sold, or used for ads. There is no account and no analytics.")
                            Bullet("Beer Debt never writes to Health Connect.")
                            Bullet("You can turn access off any time in Health Connect › App permissions › Beer Debt. The app keeps working; new runs just stop coming in.")
                        }
                    }
                }
                item {
                    GoldButton("Read the full privacy policy") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                    }
                }
                item { Text(PRIVACY_POLICY_URL, color = Palette.cream.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", color = Palette.cream.copy(alpha = 0.85f), fontSize = 15.sp)
}
