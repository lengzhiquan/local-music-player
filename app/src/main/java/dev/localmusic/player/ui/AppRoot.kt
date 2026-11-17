package dev.localmusic.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AppRoot(vm: PlayerViewModel = viewModel(LocalContext.current as ComponentActivity)) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val player by vm.player.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var viewIntent by remember { mutableStateOf(activity.intent) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.message("没有通知权限时，锁屏和控制栏可能不显示")
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<Intent> { viewIntent = it }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    LaunchedEffect(viewIntent) {
        vm.openFromIntent(viewIntent)
    }

    LaunchedEffect(Unit) {
        vm.openPlayer.collect {
            nav.navigate(ROUTE_PLAYER) { launchSingleTop = true }
        }
    }

    LaunchedEffect(player.error) {
        val error = player.error
        if (!error.isNullOrBlank()) snackbar.showSnackbar(error)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != ROUTE_PLAYER && player.hasTrack) {
                MiniPlayer(
                    state = player,
                    onOpen = { nav.navigate(ROUTE_PLAYER) },
                    onToggle = vm::toggle,
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    vm = vm,
                    onOpenPlaylist = { id -> nav.navigate("playlist/$id") },
                    onOpenCompressor = { nav.navigate(ROUTE_COMPRESSOR) },
                )
            }
            composable(
                route = "playlist/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                PlaylistScreen(
                    playlistId = id,
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { nav.navigate(ROUTE_PLAYER) },
                )
            }
            composable(ROUTE_PLAYER) {
                NowPlayingScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenCompressor = { nav.navigate(ROUTE_COMPRESSOR) },
                )
            }
            composable(ROUTE_COMPRESSOR) {
                CompressorScreen(vm = vm, onBack = { nav.popBackStack() })
            }
        }
    }
}

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_PLAYER = "player"
private const val ROUTE_COMPRESSOR = "compressor"
