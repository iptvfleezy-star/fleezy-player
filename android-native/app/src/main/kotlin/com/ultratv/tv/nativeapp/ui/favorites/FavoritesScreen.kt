package com.ultratv.tv.nativeapp.ui.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.ui.common.ContentRail
import com.ultratv.tv.nativeapp.ui.common.PosterCard
import com.ultratv.tv.nativeapp.ui.theme.UltraFonts
import com.ultratv.tv.nativeapp.ui.theme.UltraTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val playback: PlaybackContext,
    private val zapQueue: LivePlaybackQueue,
) : ViewModel() {

    private val providers = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> = providers.flatMapLatest { ps ->
        val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
            ?: return@flatMapLatest flowOf(emptyList())
        catalog.favoritesByKind(pid, "LIVE").flatMapLatest { favs ->
            catalog.channels(pid).map { list ->
                val ids = favs.map { it.remoteId }.toSet()
                list.filter { it.remoteId in ids }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val movies: StateFlow<List<MovieEntity>> = providers.flatMapLatest { ps ->
        val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
            ?: return@flatMapLatest flowOf(emptyList())
        catalog.favoritesByKind(pid, "MOVIE").flatMapLatest { favs ->
            catalog.movies(pid).map { list ->
                val ids = favs.map { it.remoteId }.toSet()
                list.filter { it.remoteId in ids }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<SeriesEntity>> = providers.flatMapLatest { ps ->
        val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
            ?: return@flatMapLatest flowOf(emptyList())
        catalog.favoritesByKind(pid, "SERIES").flatMapLatest { favs ->
            catalog.seriesList(pid).map { list ->
                val ids = favs.map { it.remoteId }.toSet()
                list.filter { it.remoteId in ids }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playChannel(channel: ChannelEntity, onReady: (String, String) -> Unit) {
        viewModelScope.launch {
            val resolved = providerRepo.resolvePlayUrl(channel.id, channel.streamUrl)
            zapQueue.set(channels.value, channel)
            playback.set(
                PlaybackContext.Item(
                    providerId = channel.providerId,
                    kind = "LIVE",
                    remoteId = channel.remoteId,
                    title = channel.name,
                    poster = channel.logo,
                    streamUrl = resolved,
                )
            )
            onReady(resolved, channel.name)
        }
    }
}

@Composable
fun FavoritesScreen(
    onPlayChannel: (String, String) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    vm: FavoritesViewModel = hiltViewModel(),
) {
    val channels by vm.channels.collectAsState()
    val movies by vm.movies.collectAsState()
    val series by vm.series.collectAsState()
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(34.dp))
        Column(Modifier.padding(horizontal = UltraTokens.EdgeGutter)) {
            Text(
                "YOUR FLEEZY",
                color = UltraTokens.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                S.favorites,
                fontFamily = UltraFonts.Serif,
                fontSize = 52.sp,
                lineHeight = 52.sp,
                color = UltraTokens.Fg,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your saved channels, movies and series in one place.",
                color = UltraTokens.Fg3,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (channels.isEmpty() && movies.isEmpty() && series.isEmpty()) {
            Text(
                S.favoritesEmpty,
                color = UltraTokens.Fg3,
                modifier = Modifier.padding(horizontal = UltraTokens.EdgeGutter),
            )
            Spacer(Modifier.height(30.dp))
        }

        ContentRail(
            title = "Live TV",
            eyebrow = "Favorites",
            items = channels,
            itemKey = { it.id },
            cardWidth = 260.dp,
        ) { c ->
            PosterCard(
                title = c.name,
                poster = c.logo,
                subtitle = "Live",
                aspect = 16f / 9f,
            ) {
                vm.playChannel(c, onPlayChannel)
            }
        }

        ContentRail(
            title = S.favoritesMoviesSection.format(movies.size),
            eyebrow = "Favorites",
            items = movies,
            itemKey = { it.id },
        ) { m ->
            PosterCard(
                title = m.name,
                poster = m.poster,
                subtitle = m.year?.toString(),
            ) { onOpenMovie(m.id) }
        }

        ContentRail(
            title = S.favoritesSeriesSection.format(series.size),
            eyebrow = "Favorites",
            items = series,
            itemKey = { it.id },
        ) { s ->
            PosterCard(
                title = s.name,
                poster = s.poster,
                subtitle = s.year?.toString(),
                placeholderEmoji = "📺",
            ) { onOpenSeries(s.id) }
        }

        Spacer(Modifier.height(40.dp))
    }
}
