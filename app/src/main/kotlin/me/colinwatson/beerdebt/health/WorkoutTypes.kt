package me.colinwatson.beerdebt.health

import androidx.health.connect.client.records.ExerciseSessionRecord as E

/**
 * Health Connect exercise types onto iOS's `HKWorkoutActivityType` raw values,
 * the vocabulary the shared ledger stores (`RunEntry.activityType`). Anything
 * without a close match is `other` (3000): it still pays its calories, and the
 * card just calls it a workout.
 */
object WorkoutTypes {
    const val OTHER = 3000

    fun activityType(exerciseType: Int): Int = when (exerciseType) {
        E.EXERCISE_TYPE_RUNNING, E.EXERCISE_TYPE_RUNNING_TREADMILL -> 37
        E.EXERCISE_TYPE_BIKING, E.EXERCISE_TYPE_BIKING_STATIONARY -> 13
        E.EXERCISE_TYPE_WALKING -> 52
        E.EXERCISE_TYPE_HIKING -> 24
        E.EXERCISE_TYPE_SWIMMING_POOL, E.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> 46
        E.EXERCISE_TYPE_ROWING, E.EXERCISE_TYPE_ROWING_MACHINE -> 35
        E.EXERCISE_TYPE_ELLIPTICAL -> 16
        E.EXERCISE_TYPE_STAIR_CLIMBING, E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> 44
        E.EXERCISE_TYPE_STRENGTH_TRAINING, E.EXERCISE_TYPE_WEIGHTLIFTING -> 50
        E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> 63
        E.EXERCISE_TYPE_YOGA -> 57
        E.EXERCISE_TYPE_PILATES -> 66
        E.EXERCISE_TYPE_DANCING -> 77
        E.EXERCISE_TYPE_TENNIS -> 48
        E.EXERCISE_TYPE_SOCCER -> 41
        E.EXERCISE_TYPE_BASKETBALL -> 6
        E.EXERCISE_TYPE_SKIING -> 61
        E.EXERCISE_TYPE_SNOWBOARDING -> 67
        E.EXERCISE_TYPE_ROCK_CLIMBING -> 9
        E.EXERCISE_TYPE_WHEELCHAIR -> 71
        else -> OTHER
    }
}
