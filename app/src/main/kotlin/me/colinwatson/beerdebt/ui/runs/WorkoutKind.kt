package me.colinwatson.beerdebt.ui.runs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Snowboarding
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.ui.graphics.vector.ImageVector
import me.colinwatson.beerdebt.engine.RunEntry

/**
 * What a workout on the books was, for its card and its notification (iOS
 * spec §28). Keyed by the `HKWorkoutActivityType` raw value the shared ledger
 * stores; names match iOS `WorkoutKind`.
 */
enum class WorkoutKind(val label: String, val icon: ImageVector) {
    RUN("Run", Icons.Default.DirectionsRun),
    RIDE("Ride", Icons.Default.DirectionsBike),
    WALK("Walk", Icons.Default.DirectionsWalk),
    HIKE("Hike", Icons.Default.Hiking),
    SWIM("Swim", Icons.Default.Pool),
    ROW("Row", Icons.Default.Rowing),
    ELLIPTICAL("Elliptical", Icons.Default.LocalFireDepartment),
    STAIRS("Stairs", Icons.Default.LocalFireDepartment),
    STRENGTH("Strength", Icons.Default.FitnessCenter),
    HIIT("HIIT", Icons.Default.LocalFireDepartment),
    YOGA("Yoga", Icons.Default.SelfImprovement),
    PILATES("Pilates", Icons.Default.SelfImprovement),
    DANCE("Dance", Icons.Default.LocalFireDepartment),
    TENNIS("Tennis", Icons.Default.SportsTennis),
    SOCCER("Soccer", Icons.Default.SportsSoccer),
    BASKETBALL("Basketball", Icons.Default.SportsBasketball),
    SKI("Ski", Icons.Default.DownhillSkiing),
    SNOWBOARD("Snowboard", Icons.Default.Snowboarding),
    CLIMB("Climb", Icons.Default.FitnessCenter),
    PUSH("Push", Icons.Default.Accessible),
    WORKOUT("Workout", Icons.Default.LocalFireDepartment);

    companion object {
        fun of(workout: RunEntry): WorkoutKind = if (workout.isRunning) RUN else when (workout.activityType) {
            13 -> RIDE
            52 -> WALK
            24 -> HIKE
            46 -> SWIM
            35 -> ROW
            16 -> ELLIPTICAL
            44, 68 -> STAIRS
            50, 20 -> STRENGTH
            63 -> HIIT
            57 -> YOGA
            66 -> PILATES
            14, 77, 78 -> DANCE
            48 -> TENNIS
            41 -> SOCCER
            6 -> BASKETBALL
            60, 61 -> SKI
            67 -> SNOWBOARD
            9 -> CLIMB
            70, 71 -> PUSH
            else -> WORKOUT
        }
    }
}
