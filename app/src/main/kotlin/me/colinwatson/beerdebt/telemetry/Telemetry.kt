package me.colinwatson.beerdebt.telemetry

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import me.colinwatson.beerdebt.BuildConfig

/**
 * The app's one door to Sentry, the Pourcraft/Pawfect Edit convention:
 * crashes and ANRs automatically, hand-reported errors with a context block
 * keyed by domain (`health`, `store`), and nothing that identifies the user
 * (no `setUser`, no breadcrumbs from screens, no session replay). The ledger
 * itself is never sent.
 */
object Telemetry {
    fun configure(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || dsn.startsWith("__")) return
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.isDebug = false
            options.environment = if (BuildConfig.DEBUG) "development" else "production"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isAnrEnabled = true
            options.tracesSampleRate = 0.0
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = true
            options.isSendDefaultPii = false
        }
    }

    fun report(error: Throwable, context: String, values: Map<String, Any?> = emptyMap()) {
        Sentry.captureException(error) { scope -> scope.setContexts(context, values) }
    }

    fun report(message: String, level: SentryLevel = SentryLevel.WARNING, context: String, values: Map<String, Any?> = emptyMap()) {
        Sentry.captureMessage(message, level) { scope -> scope.setContexts(context, values) }
    }
}
