package me.colinwatson.beerdebt.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.colinwatson.beerdebt.MainActivity
import me.colinwatson.beerdebt.R
import me.colinwatson.beerdebt.engine.Balance
import me.colinwatson.beerdebt.engine.BalanceState
import me.colinwatson.beerdebt.engine.RunEntry
import me.colinwatson.beerdebt.ui.Format

/** Local notifications for runs that arrive while the app is closed. */
class RunNotifier(private val context: Context) {
    data class Message(val title: String, val body: String)
    data class Change(
        val addedRuns: List<RunEntry>, val removedRuns: Int, val before: Balance, val after: Balance, val beersPaidOff: Int,
        /** The streak after the sync, and whether the new runs landed on a streak day. */
        val streakDays: Int = 0, val streakDay: Boolean = false,
        /** This sync made it two days in a row: interest just paused. */
        val streakActivated: Boolean = false,
    )

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Runs", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a run pays your tab"
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_WEEKLY, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A once-a-week recap of beers, miles, and your tab"
        })
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(message: Message, channel: String = CHANNEL) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    companion object {
        const val CHANNEL = "runs"
        const val CHANNEL_WEEKLY = "weekly"

        /** Pure, same copy as iOS. */
        fun message(change: Change): Message? {
            val miles = change.addedRuns.sumOf { it.distanceMiles }
            val after = change.after
            if (change.addedRuns.isEmpty()) {
                if (change.removedRuns == 0) return null
                val title = if (change.removedRuns == 1) "A run was removed from Health Connect" else "${change.removedRuns} runs were removed from Health Connect"
                return Message(title, "You're at ${standing(after)}.")
            }
            val title = if (change.addedRuns.size == 1) "Run logged: ${Format.miles(miles)}" else "${change.addedRuns.size} runs logged: ${Format.miles(miles)}"
            val body = when {
                change.before.state == BalanceState.DEBT && after.state == BalanceState.DEBT -> {
                    val knocked = maxOf(0.0, change.before.debtMiles - after.debtMiles)
                    if (change.beersPaidOff > 0) "Paid off ${beers(change.beersPaidOff)}. ${Format.miles(after.debtMiles)} still owed."
                    else "Knocked ${Format.miles(knocked)} off your tab. ${Format.miles(after.debtMiles)} still owed."
                }
                change.before.state == BalanceState.DEBT && after.state == BalanceState.CREDIT ->
                    "Tab paid, and ${Format.beersLabel(after.creditBeers)} banked. Cheers."
                change.before.state == BalanceState.DEBT && after.state == BalanceState.EVEN -> "Tab paid. Books are clean."
                after.state == BalanceState.CREDIT -> {
                    val gained = after.creditBeers - change.before.creditBeers
                    if (gained < 0.05) "You're maxed out at ${Format.beersLabel(after.creditBeers)} banked. Time to drink one."
                    else "${Format.beersLabel(after.creditBeers)} banked for later."
                }
                else -> "Books are clean."
            }
            if (change.removedRuns > 0) return Message("Runs updated", "You're at ${standing(after)}.")
            if (change.streakActivated) {
                return Message("${change.streakDays} day streak: 0% APR, earned", "$title. $body Your debt interest is now paused.")
            }
            if (change.streakDay && change.streakDays >= 2) return Message(title, "$body 🔥 ${change.streakDays} day streak, interest paused.")
            if (change.streakDay && change.streakDays == 1) return Message(title, "$body 🔥 Day one of a streak. Run 1+ mile tomorrow to pause interest.")
            return Message(title, body)
        }

        private fun standing(balance: Balance): String = when (balance.state) {
            BalanceState.DEBT -> "${Format.miles(balance.debtMiles)} owed"
            BalanceState.CREDIT -> "${Format.beersLabel(balance.creditBeers)} banked"
            BalanceState.EVEN -> "zero. Books are clean"
        }

        private fun beers(n: Int) = if (n == 1) "1 beer" else "$n beers"
    }
}
