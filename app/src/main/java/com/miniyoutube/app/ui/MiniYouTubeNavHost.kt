package com.miniyoutube.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.miniyoutube.app.AppContainer
import com.miniyoutube.app.ui.backlog.BacklogScreen
import com.miniyoutube.app.ui.backlog.BacklogViewModel
import com.miniyoutube.app.ui.channels.ChannelsScreen
import com.miniyoutube.app.ui.channels.ChannelsViewModel
import com.miniyoutube.app.ui.player.PlayerScreen
import com.miniyoutube.app.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.StateFlow

private const val BACKLOG = "backlog"
private const val CHANNELS = "channels"
private const val PLAYER = "player/{videoId}"

private fun playerRoute(videoId: String) = "player/$videoId"

@Composable
fun MiniYouTubeNavHost(
    container: AppContainer,
    launchRequest: StateFlow<LaunchRequest?>,
    onLaunchHandled: () -> Unit,
) {
    val nav = rememberNavController()
    // Both scoped to the activity rather than to their destinations. The channels one so a
    // link shared into the app can start following before its screen exists; the backlog
    // one so marking a video watched from the player shows its undo on the backlog, which
    // is where the user lands next.
    val channelsViewModel: ChannelsViewModel =
        viewModel(factory = ChannelsViewModel.factory(container))
    val backlogViewModel: BacklogViewModel =
        viewModel(factory = BacklogViewModel.factory(container))
    val request by launchRequest.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        when (val r = request) {
            null -> {
                return@LaunchedEffect
            }

            is LaunchRequest.OpenVideo -> {
                // Not launchSingleTop: that matches on the route pattern, so with a video
                // already open it would keep the old entry - and its ViewModel, holding
                // the old video - rather than open the one tapped. Popping back to the
                // backlog first replaces the player instead of stacking another.
                nav.navigate(playerRoute(r.videoId)) { popUpTo(BACKLOG) }
            }

            is LaunchRequest.Follow -> {
                channelsViewModel.follow(r.sharedText)
                nav.navigate(CHANNELS) { launchSingleTop = true }
            }
        }
        onLaunchHandled()
    }

    NavHost(navController = nav, startDestination = BACKLOG) {
        composable(BACKLOG) {
            BacklogScreen(
                viewModel = backlogViewModel,
                onOpenVideo = { videoId -> nav.navigate(playerRoute(videoId)) },
                onOpenChannels = { nav.navigate(CHANNELS) { launchSingleTop = true } },
            )
        }
        composable(CHANNELS) {
            ChannelsScreen(viewModel = channelsViewModel, onBack = { nav.popBackStack() })
        }
        composable(
            PLAYER,
            arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
        ) { entry ->
            val videoId = entry.arguments?.getString("videoId").orEmpty()
            val vm: PlayerViewModel =
                viewModel(factory = PlayerViewModel.factory(container, videoId))
            PlayerScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onMarkWatched = { id ->
                    backlogViewModel.markWatched(id)
                    nav.popBackStack()
                },
            )
        }
    }
}
