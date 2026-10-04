package me.colinwatson.beerdebt.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import me.colinwatson.beerdebt.engine.floored
import me.colinwatson.beerdebt.engine.RunEntry
import java.time.Instant
import java.util.UUID

/**
 * Read-only bridge to Health Connect: running sessions and their distance.
 * The Changes API plays the role HealthKit's anchored query plays on iOS:
 * incremental, with deletions.
 */
class HealthConnectService(private val context: Context) {
    companion object { const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata" }

    data class FetchResult(val runs: List<RunEntry>, val deletedWorkoutIDs: List<UUID>, val token: String)

    /** What the app cannot work without. [hasPermissions] checks exactly these. */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    /**
     * Reading while the app is in the background. Android 15 refuses
     * `getChangeLogs` from a worker without it, which is what broke the hourly
     * sync silently: the SecurityException classifies as PERMISSION_DENIED, so
     * it stopped being reported without ever being fixed.
     *
     * Deliberately *not* in [permissions]. A user may decline it and the app
     * still works -- it just syncs when opened instead of hourly -- and folding
     * it into the required set would report a perfectly connected user as not
     * connected.
     */
    val backgroundPermission: String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** What the permission sheet asks for: the required set plus background read. */
    val requestedPermissions: Set<String> = permissions + backgroundPermission

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Android 13 and below ship Health Connect as a Play Store app; this is true when it is missing or stale. */
    val needsInstall: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    /**
     * Where to send someone who hasn't got Health Connect, best first.
     *
     * The Play Store deep link is what the Health Connect guide prescribes,
     * but it names `com.android.vending` explicitly, so on a device without
     * the Play Store app -- an emulator, a de-Googled phone, Play disabled --
     * nothing can handle it. Launching it unguarded crashed the app with
     * `ActivityNotFoundException` for two users on 1.0+6. The web page is the
     * fallback any browser can take.
     */
    fun installIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        },
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE")
        },
    )

    /**
     * Opens the first install page the device can handle; false if it can
     * handle none, which the caller should say rather than leaving a dead tap.
     *
     * [from] is the launching activity's context, so no NEW_TASK flag is
     * needed. Both the onboarding screen and Settings go through here: the
     * crash this guards against was live in two places at once.
     */
    fun startInstall(from: Context): Boolean {
        for (intent in installIntents()) {
            try {
                from.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Nothing on this device takes it; try the next.
            }
        }
        return false
    }

    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    suspend fun hasPermissions(): Boolean =
        isAvailable && client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** Whether a background read would be allowed. False on older platforms. */
    suspend fun canReadInBackground(): Boolean =
        isAvailable && runCatching {
            client.permissionController.getGrantedPermissions().contains(backgroundPermission)
        }.getOrDefault(false)

    /**
     * Everything since [token]; a full read since [since] when there is no
     * token or it expired (dedup on the store makes the re-read harmless).
     */
    suspend fun fetchRunningWorkouts(token: String?, since: Instant): FetchResult {
        val c = client
        if (token != null) {
            val runs = mutableListOf<RunEntry>()
            val deleted = mutableListOf<UUID>()
            var next: String = token
            var expired = false
            do {
                val page = c.getChanges(next)
                if (page.changesTokenExpired) { expired = true; break }
                for (change in page.changes) {
                    when (change) {
                        is UpsertionChange -> (change.record as? ExerciseSessionRecord)?.let { session -> toRun(c, session)?.let(runs::add) }
                        is DeletionChange -> deleted.add(workoutID(change.recordId))
                    }
                }
                next = page.nextChangesToken
            } while (page.hasMore)
            if (!expired) return FetchResult(runs, deleted, next)
        }
        // First sync, or the token lapsed: read everything since the books opened, then start a fresh token.
        val fresh = c.getChangesToken(ChangesTokenRequest(recordTypes = setOf(ExerciseSessionRecord::class)))
        val sessions = c.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.after(since))
        ).records
        val runs = sessions.mapNotNull { toRun(c, it) }
        return FetchResult(runs, emptyList(), fresh)
    }

    private suspend fun toRun(c: HealthConnectClient, session: ExerciseSessionRecord): RunEntry? {
        if (session.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING &&
            session.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) return null
        val meters = c.aggregate(
            AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(session.startTime, session.endTime))
        )[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
        if (meters <= 0) return null
        return RunEntry(
            id = UUID.randomUUID(),
            healthKitWorkoutID = workoutID(session.metadata.id),
            startedAt = session.startTime.floored(),
            endedAt = session.endTime.floored(),
            distanceMeters = meters,
            importedAt = Instant.now().floored(),
            sourceName = session.metadata.dataOrigin.packageName,
        )
    }

    /** Health Connect ids are strings; the shared ledger wants a UUID. Deterministic. */
    private fun workoutID(recordId: String): UUID = UUID.nameUUIDFromBytes("healthconnect:$recordId".toByteArray())
}
