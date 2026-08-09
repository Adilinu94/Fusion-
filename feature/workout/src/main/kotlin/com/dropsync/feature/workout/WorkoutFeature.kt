package com.dropsync.feature.workout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

// Interne Routen des Trainings-Tabs; :app kennt nur WorkoutFeature
// (Modulregel 3.2: kein Feature importiert ein anderes Feature).
internal object WorkoutRoutes {
    const val SESSION = "session"
    const val LIBRARY = "library"
    const val EXERCISE = "exercise/{exerciseId}"
    const val ROUTINES = "routines"
    const val ROUTINE = "routine/{routineId}"
    const val PROGRESS = "progress"

    fun exercise(exerciseId: Long) = "exercise/$exerciseId"

    fun routine(routineId: Long) = "routine/$routineId"
}

/**
 * Einstiegspunkt des Trainings-Tabs (Schritt 12.3, erweitert): eigener
 * NavHost mit Session (Start), Uebungsbibliothek, Uebungsdetail, Routinen,
 * Routinendetail und Fortschritt. Timer und DropRest-Karte bleiben in der
 * :app-Shell darueber sichtbar.
 */
@Composable
fun WorkoutFeature(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = WorkoutRoutes.SESSION,
        modifier = modifier,
    ) {
        composable(WorkoutRoutes.SESSION) {
            SessionScreen(
                contentPadding = contentPadding,
                onOpenLibrary = { navController.navigate(WorkoutRoutes.LIBRARY) },
                onOpenRoutines = { navController.navigate(WorkoutRoutes.ROUTINES) },
                onOpenProgress = { navController.navigate(WorkoutRoutes.PROGRESS) },
            )
        }
        composable(WorkoutRoutes.LIBRARY) {
            ExerciseLibraryScreen(
                contentPadding = contentPadding,
                onOpenExercise = { id -> navController.navigate(WorkoutRoutes.exercise(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = WorkoutRoutes.EXERCISE,
            arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
        ) {
            ExerciseDetailScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WorkoutRoutes.ROUTINES) {
            RoutinesScreen(
                contentPadding = contentPadding,
                onOpenRoutine = { id -> navController.navigate(WorkoutRoutes.routine(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = WorkoutRoutes.ROUTINE,
            arguments = listOf(navArgument("routineId") { type = NavType.LongType }),
        ) {
            RoutineEditScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WorkoutRoutes.PROGRESS) {
            ProgressScreen(
                contentPadding = contentPadding,
                onOpenExercise = { id -> navController.navigate(WorkoutRoutes.exercise(id)) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
