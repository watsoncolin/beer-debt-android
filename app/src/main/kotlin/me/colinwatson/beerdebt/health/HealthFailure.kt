package me.colinwatson.beerdebt.health

import android.os.DeadObjectException
import android.os.RemoteException
import java.io.IOException

/**
 * What a thrown Health Connect error actually means, so the app can tell an
 * environment condition from a defect. The port of iOS `HealthFailure`.
 *
 * The one catch in [HealthSync] used to do two things with whatever it caught:
 * report it to Sentry at error level, and put the exception's own `message` in
 * front of the user. Both were wrong. A background sync runs roughly hourly
 * whatever the phone is doing, so a revoked permission or a provider that has
 * been updated out from under us would page on every wake until someone
 * noticed, burying anything genuine. And the strings these throw name no
 * remedy -- some have no message at all, which rendered as "null" in Settings.
 *
 * Health Connect does not expose an error hierarchy of its own at 1.1.0; it
 * wraps platform exceptions. So this keys off the types those actually are,
 * plus the provider state [HealthConnectService] already tracks, rather than
 * matching on message text.
 *
 * Pure, so the mapping is tested rather than trusted.
 */
enum class HealthFailure {
    /** Permission was never granted, or the user has since turned it off. */
    PERMISSION_DENIED,

    /** No Health Connect on this device, so there is nothing to read. */
    PROVIDER_UNAVAILABLE,

    /**
     * Health Connect is missing or too old for the APIs we call. One case, not
     * two: the platform reports both as SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
     * and the remedy for both is the Play Store, which is why
     * [HealthConnectService.needsInstall] covers them together.
     */
    PROVIDER_NEEDS_INSTALL,

    /**
     * The Health Connect process went away mid-call. Routine: the system kills
     * it like any other app, and the next sync reconnects.
     */
    PROVIDER_GONE,

    /** Transient disk or IPC trouble. The next sync is the retry. */
    TRANSIENT,

    /** Not recognised. The only kind worth reporting. */
    UNKNOWN;

    /**
     * Only what we could not explain. Everything named above is the phone, the
     * provider, or the user -- not the app -- and reporting it is noise that
     * hides the rest.
     */
    val isReportable: Boolean get() = this == UNKNOWN

    /** Words with a remedy in them, or [fallback] when we have nothing better. */
    fun message(fallback: String): String = when (this) {
        PERMISSION_DENIED ->
            "Health Connect access is off for Beer Debt. Turn it on in Health Connect › App permissions › Beer Debt."
        PROVIDER_UNAVAILABLE ->
            "Health Connect isn't available on this device, so runs can't be read."
        PROVIDER_NEEDS_INSTALL ->
            "Install Health Connect to let Beer Debt read your runs."
        PROVIDER_GONE ->
            "Health Connect closed while we were reading. Your runs are safe and will be read again next sync."
        TRANSIENT ->
            "Couldn't reach Health Connect just now. Your runs will be read again next sync."
        UNKNOWN -> fallback
    }

    companion object {
        /**
         * Classify [error]. [available] and [needsInstall] come from
         * [HealthConnectService], which reads the provider's SDK status
         * directly -- more reliable than inferring it from the throw, since an
         * absent provider surfaces as a plain IllegalStateException.
         */
        fun of(error: Throwable, available: Boolean = true, needsInstall: Boolean = false): HealthFailure {
            // Provider state first: it explains the throw regardless of type,
            // and an unavailable provider is the commonest reason getOrCreate
            // fails at all.
            if (needsInstall) return PROVIDER_NEEDS_INSTALL
            if (!available) return PROVIDER_UNAVAILABLE

            // The cause is often wrapped a couple of levels down by the client's
            // own IPC layer, so walk the chain. The cap stops a self-referencing
            // chain from spinning.
            var cause: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            while (cause != null && seen.size < 8 && seen.add(cause)) {
                when (cause) {
                    is SecurityException -> return PERMISSION_DENIED
                    is DeadObjectException -> return PROVIDER_GONE
                    is RemoteException -> return PROVIDER_GONE
                    is IOException -> return TRANSIENT
                }
                cause = cause.cause
            }
            return UNKNOWN
        }
    }
}
