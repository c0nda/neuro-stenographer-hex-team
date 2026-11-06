package com.vorontsov.neurostenographer.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vorontsov.neurostenographer.presentation.history.HistoryScreen
import com.vorontsov.neurostenographer.presentation.home.HomeScreen
import com.vorontsov.neurostenographer.presentation.recording.RecordingScreen
import com.vorontsov.neurostenographer.presentation.speakers.SpeakersScreen
import com.vorontsov.neurostenographer.presentation.transcript.TranscriptScreen

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Destinations.HOME,
    ) {
        composable(Destinations.HOME) {
            HomeScreen(
                onStartRecording = { navController.navigate(Destinations.RECORDING) },
                onOpenRecording = { recordingId ->
                    navController.navigate(Destinations.transcript(recordingId))
                },
                onOpenHistory = { navController.navigate(Destinations.HISTORY) },
            )
        }

        composable(Destinations.RECORDING) {
            RecordingScreen(
                onBack = { navController.popBackStack() },
                onRecordingFinished = { recordingId ->
                    navController.navigate(Destinations.transcript(recordingId)) {
                        popUpTo(Destinations.RECORDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Destinations.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenRecording = { recordingId ->
                    navController.navigate(Destinations.transcript(recordingId))
                },
            )
        }

        composable(
            route = Destinations.TRANSCRIPT,
            arguments = listOf(
                navArgument(Destinations.RECORDING_ID_ARG) { type = NavType.LongType },
            ),
        ) { entry ->
            val recordingId = entry.arguments
                ?.getLong(Destinations.RECORDING_ID_ARG)
                ?: return@composable
            TranscriptScreen(
                recordingId = recordingId,
                onBack = { navController.popBackStack() },
                onOpenSpeakers = { navController.navigate(Destinations.speakers(recordingId)) },
            )
        }

        composable(
            route = Destinations.SPEAKERS,
            arguments = listOf(
                navArgument(Destinations.RECORDING_ID_ARG) { type = NavType.LongType },
            ),
        ) {
            SpeakersScreen(onBack = { navController.popBackStack() })
        }
    }
}
