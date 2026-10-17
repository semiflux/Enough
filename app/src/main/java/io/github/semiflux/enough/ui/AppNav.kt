package io.github.semiflux.enough.ui

import android.app.Application
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private const val PUSH_MS = 350

/** Push transition: new screens slide in from the right, back reverses it. */
private fun AnimatedContentTransitionScope<*>.pushEnter() =
    slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(PUSH_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(tween(PUSH_MS))

private fun AnimatedContentTransitionScope<*>.pushExit() =
    slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(PUSH_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(tween(PUSH_MS))

private fun AnimatedContentTransitionScope<*>.popEnter() =
    slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(PUSH_MS, easing = FastOutSlowInEasing)
    ) + fadeIn(tween(PUSH_MS))

private fun AnimatedContentTransitionScope<*>.popExit() =
    slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(PUSH_MS, easing = FastOutSlowInEasing)
    ) + fadeOut(tween(PUSH_MS))

@Composable
fun AppNav(deepLinkPackage: String? = null) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() }
    ) {
        composable("home") {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(application))
            HomeScreen(
                viewModel = vm,
                onAdd = { nav.navigate("picker") },
                onOpenDetail = { pkg -> nav.navigate("detail/$pkg") }
            )
        }
        composable("picker") {
            val vm: PickerViewModel = viewModel(factory = PickerViewModel.Factory(application))
            PickerScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onAdded = { pkg ->
                    nav.popBackStack()
                    nav.navigate("detail/$pkg")
                }
            )
        }
        composable(
            route = "detail/{pkg}",
            arguments = listOf(navArgument("pkg") { type = NavType.StringType })
        ) { entry ->
            val pkg = entry.arguments?.getString("pkg") ?: return@composable
            val vm: DetailViewModel = viewModel(
                key = pkg,
                factory = DetailViewModel.Factory(application, pkg)
            )
            DetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }

    // Opened from the block screen's "Change limit".
    if (deepLinkPackage != null) {
        androidx.compose.runtime.LaunchedEffect(deepLinkPackage) {
            nav.navigate("detail/$deepLinkPackage")
        }
    }
}
