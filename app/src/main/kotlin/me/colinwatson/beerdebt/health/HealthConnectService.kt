package me.colinwatson.beerdebt.health

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
import me.colinwatson.beerdebt.data.floored
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

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Android 13 and below ship Health Connect as a Play Store app; this is true when it is missing or stale. */
    val needsInstall: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    /** Play Store deep link to Health Connect's onboarding, as the Health Connect guide prescribes. */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage("com.android.vending")
        data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
    }

    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    suspend fun hasPermissions(): Boolean =
        isAvailable && client.permissionController.getGrantedPermissions().containsAll(permissions)

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
