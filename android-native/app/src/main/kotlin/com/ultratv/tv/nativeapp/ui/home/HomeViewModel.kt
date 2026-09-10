package com.ultratv.tv.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val provider: ProviderRepository,
    private val catalog: CatalogRepository,
    private val history: HistoryRepository,
    private val playback: PlaybackContext,
    private val zapQueue: LivePlaybackQueue,
) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = provider.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val pid = providers.map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }

    val continueWatching: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.continueWatching(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyWatched: StateFlow<List<WatchHistoryEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.recent(id, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredMovies: StateFlow<List<MovieEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.moviesLimited(id, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredSeries: StateFlow<List<SeriesEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.seriesLimited(id, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featuredChannels: StateFlow<List<ChannelEntity>> = pid
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.channelsLimited(id, 30) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Starts an item from Home history. Live entries are reconnected to their
     * current ChannelEntity so URL resolution and D-pad zapping keep working.
     */
    fun playFromHistory(
        h: WatchHistoryEntity,
        onReady: (url: String, title: String) -> Unit,
    ) {
        if (h.kind == "LIVE") {
            viewModelScope.launch {
                val channel = catalog.channelByRemoteId(h.providerId, h.remoteId)
                if (channel == null) {
                    playStoredHistory(h, onReady)
                    return@launch
                }

                val recentQueue = mutableListOf<ChannelEntity>()
                val seen = HashSet<String>()
                for (item in recentlyWatched.value) {
                    if (item.kind != "LIVE" || !seen.add(item.remoteId)) continue
                    catalog.channelByRemoteId(h.providerId, item.remoteId)?.let(recentQueue::add)
                }
                startLive(
                    channel = channel,
                    queue = recentQueue.ifEmpty { listOf(channel) },
                    onReady = onReady,
                )
            }
            return
        }

        playStoredHistory(h, onReady)
    }

    private fun playStoredHistory(
        h: WatchHistoryEntity,
        onReady: (url: String, title: String) -> Unit,
    ) {
        // Movies/episodes (and a removed Live channel fallback) must not
        // inherit a stale Live zap queue.
        zapQueue.clear()
        playback.set(
            PlaybackContext.Item(
                providerId = h.providerId,
                kind = h.kind,
                remoteId = h.remoteId,
                title = h.title,
                poster = h.poster,
                streamUrl = h.streamUrl,
                parentRemoteId = h.parentRemoteId,
            )
        )
        onReady(h.streamUrl, h.title)
    }

    /** Seeds a compact Home Live queue so UP/DOWN keeps working in the player. */
    fun playChannel(
        channel: ChannelEntity,
        onReady: (url: String, title: String) -> Unit,
    ) {
        val featured = featuredChannels.value
        val queue = if (featured.any { it.id == channel.id }) featured else listOf(channel)
        viewModelScope.launch {
            startLive(channel, queue.ifEmpty { listOf(channel) }, onReady)
        }
    }

    private suspend fun startLive(
        channel: ChannelEntity,
        queue: List<ChannelEntity>,
        onReady: (url: String, title: String) -> Unit,
    ) {
        val resolved = provider.resolvePlayUrl(channel.id, channel.streamUrl)
        zapQueue.set(queue, channel)
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

    /** Removes an entry from history (used by "Dismiss" on Continue watching). */
    fun dismiss(h: WatchHistoryEntity) {
        viewModelScope.launch { history.remove(h.providerId, h.kind, h.remoteId) }
    }

    private val _refreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        viewModelScope.launch {
            val id = providers.value.firstOrNull { it.active }?.id
                ?: providers.value.firstOrNull()?.id
                ?: return@launch
            _refreshing.value = true
            try { provider.syncAll(id) } finally { _refreshing.value = false }
        }
    }
}
