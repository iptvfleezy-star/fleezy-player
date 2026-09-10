package com.ultratv.tv.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.ultratv.tv.nativeapp.data.prefs.SidebarPosition
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.data.sync.SyncScheduler
import com.ultratv.tv.nativeapp.nav.Routes
import com.ultratv.tv.nativeapp.ui.AppViewModel
import com.ultratv.tv.nativeapp.ui.categories.CategoriesScreen
import com.ultratv.tv.nativeapp.ui.common.FormFactor
import com.ultratv.tv.nativeapp.ui.common.rememberFormFactor
import com.ultratv.tv.nativeapp.ui.components.BottomBarNav
import com.ultratv.tv.nativeapp.ui.components.SidebarNav
import com.ultratv.tv.nativeapp.ui.components.TopBarNav
import com.ultratv.tv.nativeapp.ui.favorites.FavoritesScreen
import com.ultratv.tv.nativeapp.ui.guide.GuideGridScreen
import com.ultratv.tv.nativeapp.ui.home.HomeScreen
import com.ultratv.tv.nativeapp.ui.live.LiveScreen
import com.ultratv.tv.nativeapp.ui.movies.MovieDetailScreen
import com.ultratv.tv.nativeapp.ui.movies.MoviesScreen
import com.ultratv.tv.nativeapp.ui.player.PlayerScreen
import com.ultratv.tv.nativeapp.ui.search.SearchScreen
import com.ultratv.tv.nativeapp.ui.series.SeriesDetailScreen
import com.ultratv.tv.nativeapp.ui.series.SeriesScreen
import com.ultratv.tv.nativeapp.ui.settings.SettingsScreen
import com.ultratv.tv.nativeapp.ui.theme.UltraTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Carries a one-shot "open this URL+title in the player as soon as the
 * Composition is up" intent. Set by [MainActivity.kickoffStartupTasks] when
 * `autoPlayLastOnLaunch` is enabled; consumed by [UltraTvAppRoot] once.
 */
object StartupNav {
    data class Pending(val url: String, val title: String)
    val pending = MutableStateFlow<Pending?>(null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefsStore: UserPreferencesStore
    @Inject lateinit var providerRepo: ProviderRepository
    @Inject lateinit var historyRepo: HistoryRepository
    @Inject lateinit var playback: PlaybackContext
    @Inject lateinit var channelDao: ChannelDao
    @Inject lateinit var zapQueue: LivePlaybackQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Root() }
        kickoffStartupTasks()
    }

    /**
     * When the user presses Home while a stream is playing, enter PiP so the
     * stream keeps going in a corner. Falls back silently on devices that
     * don't support it (some TV firmwares).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (playback.current.value == null) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /**
     * Best-effort startup tasks. Both are gated by user prefs.
     *
     *  1. Auto-sync providers if `autoSyncOnLaunch` is on AND the configured
     *     [UserPrefs.syncIntervalHours] interval has elapsed since the last
     *     successful sync. Interval 0 means "every launch".
     *  2. Auto-play the most recently watched item by emitting a Pending
     *     entry on [StartupNav.pending], which the NavGraph picks up.
     */
    private fun kickoffStartupTasks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = prefsStore.flow.first()

            // (Re-)apply the background sync schedule from the stored prefs
            // every time the app starts so a re-install / OS restart picks up
            // where we left off.
            SyncScheduler.schedule(
                this@MainActivity,
                if (prefs.autoSyncOnLaunch) prefs.syncIntervalHours else 0,
            )

            if (prefs.autoSyncOnLaunch) {
                val intervalMs = prefs.syncIntervalHours * 3600L * 1000L
                val due = intervalMs == 0L || (System.currentTimeMillis() - prefs.lastSyncAtMs) >= intervalMs
                if (due) {
                    runCatching {
                        val all = providerRepo.observeProviders().first()
                        all.forEach { p -> providerRepo.syncAll(p.id) }
                        // Only mark the refresh timestamp when every provider
                        // completed successfully. A failure stays due for retry.
                        prefsStore.setLastSyncAt(System.currentTimeMillis())
                    }
                }
            }

            if (prefs.autoPlayLastOnLaunch) {
                val providers = providerRepo.observeProviders().first()
                val activeProvider = providers.firstOrNull { it.active } ?: providers.firstOrNull()
                if (activeProvider != null) {
                    val recent = historyRepo.recent(activeProvider.id, 20).first()
                    val last = recent.firstOrNull()
                    if (last != null) {
                        if (last.kind == "LIVE") {
                            // Reconnect history to the current catalog. Provider stream
                            // URLs can change between launches, and the player needs a
                            // fresh zap queue for UP/DOWN to work after auto-resume.
                            val current = channelDao.byRemoteId(activeProvider.id, last.remoteId)
                            if (current != null) {
                                val liveHistory = recent
                                    .filter { it.kind == "LIVE" }
                                    .distinctBy { it.remoteId }
                                val resolvedQueue = mutableListOf<com.ultratv.tv.nativeapp.data.db.ChannelEntity>()
                                for (item in liveHistory) {
                                    channelDao.byRemoteId(activeProvider.id, item.remoteId)
                                        ?.let(resolvedQueue::add)
                                }
                                val queue = resolvedQueue.ifEmpty { listOf(current) }
                                val resolved = providerRepo.resolvePlayUrl(current.id, current.streamUrl)
                                zapQueue.set(queue, current)
                                playback.set(
                                    PlaybackContext.Item(
                                        providerId = current.providerId,
                                        kind = "LIVE",
                                        remoteId = current.remoteId,
                                        title = current.name,
                                        poster = current.logo,
                                        streamUrl = resolved,
                                    )
                                )
                                StartupNav.pending.value = StartupNav.Pending(resolved, current.name)
                            } else {
                                // The saved channel may have been removed from the latest
                                // playlist. Preserve legacy behavior as a best-effort fallback.
                                zapQueue.clear()
                                playback.set(
                                    PlaybackContext.Item(
                                        providerId = last.providerId,
                                        kind = last.kind,
                                        remoteId = last.remoteId,
                                        title = last.title,
                                        poster = last.poster,
                                        streamUrl = last.streamUrl,
                                        parentRemoteId = last.parentRemoteId,
                                    )
                                )
                                StartupNav.pending.value = StartupNav.Pending(last.streamUrl, last.title)
                            }
                        } else {
                            zapQueue.clear()
                            playback.set(
                                PlaybackContext.Item(
                                    providerId = last.providerId,
                                    kind = last.kind,
                                    remoteId = last.remoteId,
                                    title = last.title,
                                    poster = last.poster,
                                    streamUrl = last.streamUrl,
                                    parentRemoteId = last.parentRemoteId,
                                )
                            )
                            StartupNav.pending.value = StartupNav.Pending(last.streamUrl, last.title)
                        }
                    }
                }
            }
        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun Root(vm: AppViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsState()
    val lang = com.ultratv.tv.nativeapp.i18n.AppLang.fromCode(prefs.language)
    val strings = com.ultratv.tv.nativeapp.i18n.stringsFor(lang)
    val direction = if (lang == com.ultratv.tv.nativeapp.i18n.AppLang.Arabic)
        androidx.compose.ui.unit.LayoutDirection.Rtl
    else
        androidx.compose.ui.unit.LayoutDirection.Ltr
    androidx.compose.runtime.CompositionLocalProvider(
        com.ultratv.tv.nativeapp.i18n.LocalStrings provides strings,
        androidx.compose.ui.platform.LocalLayoutDirection provides direction,
    ) {
        UltraTvTheme(theme = prefs.theme) {
            UltraTvAppRoot(prefs.sidebarPosition)
            // First-run wizard renders itself as a full-screen overlay only
            // when no provider is configured AND the user hasn't dismissed it.
            com.ultratv.tv.nativeapp.ui.onboarding.OnboardingWizard(
                onOpenSettings = { /* user can re-enter Settings via sidebar */ },
            )
        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun UltraTvAppRoot(sidebarPosition: SidebarPosition) {
    val nav = rememberNavController()
    val currentBackStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route.orEmpty()
    val playerActive = currentRoute.startsWith("player")
    val form = rememberFormFactor()
    // Effective nav style: phone-portrait collapses to a bottom bar regardless
    // of the user's "sidebar / top bar" preference, otherwise we honour it.
    val useBottomBar = form == FormFactor.Compact
    val useTopBar = !useBottomBar && (sidebarPosition == SidebarPosition.TOP || form == FormFactor.Medium)

    // One-shot: as soon as we have a NavController, consume any pending
    // auto-play request set during startup.
    val pending by StartupNav.pending.collectAsState()
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        nav.navigate(Routes.player(p.url, p.title))
        StartupNav.pending.value = null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        when {
            useBottomBar -> Column(Modifier.fillMaxSize()) {
                if (!playerActive) {
                    com.ultratv.tv.nativeapp.ui.common.SyncStatusBanner()
                }
                Box(
                    Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            if (playerActive) PaddingValues(0.dp)
                            else PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ),
                ) { NavGraph(nav) }
                if (!playerActive) {
                    BottomBarNav(navController = nav)
                }
            }
            useTopBar -> Column(Modifier.fillMaxSize()) {
                if (!playerActive) {
                    com.ultratv.tv.nativeapp.ui.common.SyncStatusBanner()
                    TopBarNav(navController = nav)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            if (playerActive) PaddingValues(0.dp)
                            else PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
                        ),
                ) { NavGraph(nav) }
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (!playerActive) {
                    com.ultratv.tv.nativeapp.ui.common.SyncStatusBanner()
                }
                Row(Modifier.fillMaxSize()) {
                    if (!playerActive) {
                        SidebarNav(navController = nav)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(
                                if (playerActive) PaddingValues(0.dp)
                                else PaddingValues(start = 12.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
                            ),
                    ) { NavGraph(nav) }
                }
            }
        }
        com.ultratv.tv.nativeapp.ui.common.ToasterHost()
        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun NavGraph(nav: androidx.navigation.NavHostController) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onGoLive = { nav.navigate(Routes.LIVE) },
                onGoMovies = { nav.navigate(Routes.MOVIES) },
                onGoSeries = { nav.navigate(Routes.SERIES) },
                onGoGuide = { nav.navigate(Routes.GUIDE) },
                onGoFavorites = { nav.navigate(Routes.FAVORITES) },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        composable(Routes.LIVE) {
            LiveScreen(onPlay = { url, title -> nav.navigate(Routes.player(url, title)) })
        }
        composable(Routes.MOVIES) {
            MoviesScreen(onOpen = { id -> nav.navigate(Routes.movieDetail(id)) })
        }
        composable(
            Routes.MOVIE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            MovieDetailScreen(
                movieId = id,
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(Routes.SERIES) {
            SeriesScreen(onOpen = { id -> nav.navigate(Routes.seriesDetail(id)) })
        }
        composable(
            Routes.SERIES_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            SeriesDetailScreen(
                seriesId = id,
                onPlayEpisode = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        composable(Routes.GUIDE) {
            GuideGridScreen(
                onPlayChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable("categories") { CategoriesScreen() }
        composable("locked-channels") { com.ultratv.tv.nativeapp.ui.parental.LockedChannelsScreen() }
        composable("recordings") {
            com.ultratv.tv.nativeapp.ui.recordings.RecordingsScreen(
                onPlayLocal = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(
                onPlayChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onNavigate = { route -> nav.navigate(route) }) }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val rawUrl = entry.arguments?.getString("url").orEmpty()
            val rawTitle = entry.arguments?.getString("title").orEmpty()
            val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
            val title = java.net.URLDecoder.decode(rawTitle, "UTF-8")
            PlayerScreen(url = url, title = title, onBack = { nav.popBackStack() })
        }
    }
}
