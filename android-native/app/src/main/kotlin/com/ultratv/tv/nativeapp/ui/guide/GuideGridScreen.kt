package com.ultratv.tv.nativeapp.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.EpgDao
import com.ultratv.tv.nativeapp.data.db.EpgEntity
import com.ultratv.tv.nativeapp.data.prefs.MyGroup
import com.ultratv.tv.nativeapp.data.prefs.MyGroupsStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Time window represented horizontally. We render 8h at a time around the
 *  user's current scroll anchor; 1 hour = 240 dp on screen. */
private const val PX_PER_HOUR_DP = 240
private const val PX_PER_MIN_DP = PX_PER_HOUR_DP / 60f
private const val ROW_HEIGHT_DP = 68
private const val FILTER_DEFAULT = "__default__"
private const val FILTER_MY_GROUP_PREFIX = "__my_group__:"
private fun myGroupFilterId(groupId: String): String = FILTER_MY_GROUP_PREFIX + groupId

/** Provider-wide EPG view. Channel data is category-scoped by default so
 *  large Xtream lineups do not materialize every Live channel or query EPG
 *  for thousands of rows just because the Guide screen was opened. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideGridViewModel @Inject constructor(
    providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val provider: ProviderRepository,
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val myGroupsStore: MyGroupsStore,
    private val playback: com.ultratv.tv.nativeapp.data.repo.PlaybackContext,
    private val zapQueue: com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue,
) : ViewModel() {

    private val activeProviderId: StateFlow<Long?> = providerRepo.observeProviders()
        .map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categories: StateFlow<List<CategoryEntity>> = activeProviderId
        .flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList()) else catalog.categories(pid, "LIVE")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val myGroups: StateFlow<List<MyGroup>> =
        combine(activeProviderId, myGroupsStore.groups) { pid, groups ->
            if (pid == null) emptyList() else groups.filter { it.providerId == pid }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Do not start Guide on ALL. Some providers expose many thousands of Live
    // channels, which used to trigger an immediate provider-wide Room + EPG
    // query before the user had chosen what they wanted to browse.
    private val _filter = MutableStateFlow(FILTER_DEFAULT)
    val filter: StateFlow<String> = _filter.asStateFlow()

    val channels: StateFlow<List<ChannelEntity>> = combine(
        activeProviderId,
        _filter,
    ) { pid, selected -> pid to selected }
        .flatMapLatest { (pid, selected) ->
            when {
                pid == null || selected == FILTER_DEFAULT -> flowOf(emptyList())
                selected == "ALL" -> catalog.channels(pid)
                selected == "FAVORITES" -> {
                    // Favorites are normally small. Resolve only the saved IDs
                    // instead of loading every channel and filtering in memory.
                    catalog.favoritesByKind(pid, "LIVE").map { favs ->
                        favs.mapNotNull { fav -> channelDao.byRemoteId(pid, fav.remoteId) }
                    }
                }
                selected.startsWith(FILTER_MY_GROUP_PREFIX) -> {
                    val groupId = selected.removePrefix(FILTER_MY_GROUP_PREFIX)
                    myGroupsStore.members.map { memberships ->
                        val remoteIds = memberships.asSequence()
                            .filter { it.providerId == pid && it.groupId == groupId }
                            .map { it.remoteId }
                            .distinct()
                            .toList()
                        if (remoteIds.isEmpty()) {
                            emptyList()
                        } else {
                            remoteIds.chunked(500)
                                .flatMap { ids -> channelDao.byRemoteIds(pid, ids) }
                                .sortedWith(
                                    compareBy<ChannelEntity> { if (it.userPosition == 0) 1 else 0 }
                                        .thenBy { it.userPosition }
                                        .thenBy { it.name.lowercase() },
                                )
                        }
                    }
                }
                else -> catalog.channelsForCategory(pid, selected)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectFilter(value: String) {
        _filter.value = value
    }

    init {
        // IPTVBoss / Xtream category order becomes the natural Guide landing
        // point. ALL is still available explicitly, but opening Guide no longer
        // performs the heaviest possible query by default.
        viewModelScope.launch {
            categories.collect { list ->
                if (_filter.value == FILTER_DEFAULT && list.isNotEmpty()) {
                    _filter.value = list.first().remoteId
                }
            }
        }
    }

    private val _programmes = MutableStateFlow<Map<Long, List<EpgEntity>>>(emptyMap())
    val programmes: StateFlow<Map<Long, List<EpgEntity>>> = _programmes.asStateFlow()

    // The player updates this identity on every Live zap. Guide uses it only
    // to restore the row the customer actually finished watching.
    val currentPlayback: StateFlow<com.ultratv.tv.nativeapp.data.repo.PlaybackContext.Item?> =
        playback.current

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var reloadJob: kotlinx.coroutines.Job? = null
    private var lastRequestedChannels: List<ChannelEntity> = emptyList()

    fun playChannel(
        channel: ChannelEntity,
        onReady: (url: String, title: String) -> Unit,
    ) {
        viewModelScope.launch {
            val resolved = runCatching {
                provider.resolvePlayUrl(channel.id, channel.streamUrl)
            }.getOrElse {
                com.ultratv.tv.nativeapp.ui.common.Toaster.show("Unable to open channel. Try again.")
                return@launch
            }
            zapQueue.set(channels.value, channel)
            playback.set(
                com.ultratv.tv.nativeapp.data.repo.PlaybackContext.Item(
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

    /** Pull a fresh xmltv into the EPG table. */
    fun refreshXmltv() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val activeId = activeProviderId.value ?: return@launch
                provider.syncXmltv(activeId) { /* SyncStatusBus handles UI */ }
            } finally {
                _loading.value = false
                // Refresh only the Guide rows the user is currently browsing.
                // Using channels.value here would turn an explicit ALL filter
                // back into a provider-wide EPG query after every XMLTV sync.
                reloadFor(lastRequestedChannels)
            }
        }
    }

    /** Reload programmes for the visible channels for the next 12 h window. */
    fun reloadFor(visible: List<ChannelEntity>) {
        lastRequestedChannels = visible
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (visible.isEmpty()) {
                _programmes.value = emptyMap()
                return@launch
            }
            val now = System.currentTimeMillis()
            val end = now + 12 * 60 * 60 * 1000L
            // The UI supplies a small window around the rows currently on
            // screen. Chunking remains a safety net for SQLite's host limit.
            val flat = visible.map { it.id }.chunked(500).flatMap { ids ->
                epgDao.rangeForChannels(ids, now - 60 * 60_000, end)
            }
            _programmes.value = flat.groupBy { it.channelId }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun GuideGridScreen(
    onPlayChannel: (String, String) -> Unit,
    vm: GuideGridViewModel = hiltViewModel(),
) {
    val channels by vm.channels.collectAsState()
    val categories by vm.categories.collectAsState()
    val myGroups by vm.myGroups.collectAsState()
    val selectedFilter by vm.filter.collectAsState()
    val byChannel by vm.programmes.collectAsState()
    val loading by vm.loading.collectAsState()
    val currentPlayback by vm.currentPlayback.collectAsState()
    val channelListState = rememberLazyListState()
    val filterListState = rememberLazyListState()
    val channelFocusRequester = remember { FocusRequester() }
    var restoreFocusIndex by remember { mutableStateOf(-1) }
    var lastPositionedFilter by remember { mutableStateOf<String?>(null) }
    val firstVisibleChannelIndex = channelListState.firstVisibleItemIndex

    // Keep EPG work bounded even when the customer explicitly selects ALL.
    // We preload a generous window around the visible rows so D-pad scrolling
    // remains smooth without querying thousands of channels at once.
    LaunchedEffect(channels, firstVisibleChannelIndex) {
        if (channels.isEmpty()) {
            vm.reloadFor(emptyList())
        } else {
            val anchor = firstVisibleChannelIndex.coerceIn(0, channels.lastIndex)
            val from = (anchor - 20).coerceAtLeast(0)
            val to = (anchor + 80).coerceAtMost(channels.size)
            vm.reloadFor(channels.subList(from, to))
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    // Time axis: 12h window starting at "now floored to top of hour minus 30 min".
    val windowStart = remember(now) { (now / 3_600_000L) * 3_600_000L - 30 * 60_000L }
    val windowEnd = remember(windowStart) { windowStart + 12 * 60 * 60 * 1000L }

    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
    val F = com.ultratv.tv.nativeapp.ui.theme.UltraFonts
    val hScroll = rememberScrollState()
    val guideScope = androidx.compose.runtime.rememberCoroutineScope()

    // Restore the channel the fullscreen player actually ended on. If the
    // customer switches filters while staying in Guide, start the new filter
    // at row one and do not steal D-pad focus from the filter rail.
    LaunchedEffect(selectedFilter, channels, currentPlayback?.remoteId) {
        if (channels.isEmpty()) return@LaunchedEffect

        val firstPositionForScreen = lastPositionedFilter == null
        val filterChanged =
            lastPositionedFilter != null && lastPositionedFilter != selectedFilter

        if (firstPositionForScreen) {
            val playingIndex = currentPlayback
                ?.takeIf { it.kind == "LIVE" }
                ?.let { playing ->
                    channels.indexOfFirst {
                        it.providerId == playing.providerId &&
                            it.remoteId == playing.remoteId
                    }
                }
                ?: -1

            restoreFocusIndex = playingIndex
            channelListState.scrollToItem(playingIndex.takeIf { it >= 0 } ?: 0)

            if (playingIndex >= 0) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching { channelFocusRequester.requestFocus() }
            }
        } else if (filterChanged) {
            restoreFocusIndex = -1
            channelListState.scrollToItem(0)
        }

        lastPositionedFilter = selectedFilter
    }

    // Keep the selected Guide filter chip on-screen when the provider has a
    // long category list. This scrolls the rail but never changes focus.
    LaunchedEffect(selectedFilter, categories, myGroups) {
        val index = when (selectedFilter) {
            "ALL" -> 0
            "FAVORITES" -> 1
            FILTER_DEFAULT -> -1
            else -> {
                val groupIndex = myGroups.indexOfFirst { myGroupFilterId(it.id) == selectedFilter }
                if (groupIndex >= 0) {
                    groupIndex + 2
                } else {
                    categories.indexOfFirst { it.remoteId == selectedFilter }
                        .takeIf { it >= 0 }
                        ?.plus(2 + myGroups.size)
                        ?: -1
                }
            }
        }
        if (index >= 0) filterListState.scrollToItem(index)
    }
    Column(Modifier.fillMaxSize()) {
        // Editorial header
        androidx.compose.foundation.layout.Spacer(Modifier.height(40.dp))
        Column(Modifier.padding(start = T.EdgeGutter, end = T.EdgeGutter, bottom = 20.dp)) {
            Text(
                "TV GUIDE",
                color = T.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    S.tvGuide,
                    fontFamily = F.Serif,
                    fontSize = 48.sp,
                    lineHeight = 48.sp,
                    letterSpacing = (-1.4).sp,
                    color = T.Fg,
                )
                val total = byChannel.values.sumOf { it.size }
                Text(
                    S.guideProgrammesTemplate.format(total, channels.size),
                    fontSize = 13.sp,
                    color = T.Fg3,
                )
                Button(
                    onClick = { guideScope.launch { hScroll.animateScrollTo(0) } },
                    colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = T.AccentSoft),
                ) {
                    Text("NOW", fontSize = 13.sp, color = T.Fg)
                }
                Button(
                    onClick = { vm.refreshXmltv() },
                    enabled = !loading,
                    colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = T.Surface2),
                ) {
                    Text(if (loading) S.guideLoading else "REFRESH GUIDE", fontSize = 13.sp, color = T.Fg2)
                }
            }
        }

        // Fast TV-first filtering. Large providers can expose thousands of
        // channels, so Guide should never require scrolling one enormous list.
        LazyRow(
            state = filterListState,
            contentPadding = PaddingValues(start = T.EdgeGutter, end = T.EdgeGutter, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("guide-all") {
                GuideFilterChip(
                    label = "ALL",
                    selected = selectedFilter == "ALL",
                    onClick = { vm.selectFilter("ALL") },
                )
            }
            item("guide-favorites") {
                GuideFilterChip(
                    label = "★ FAVORITES",
                    selected = selectedFilter == "FAVORITES",
                    onClick = { vm.selectFilter("FAVORITES") },
                )
            }
            items(myGroups, key = { "guide-group-" + it.id }) { group ->
                val filterId = myGroupFilterId(group.id)
                GuideFilterChip(
                    label = "◆ " + group.name.uppercase(),
                    selected = selectedFilter == filterId,
                    onClick = { vm.selectFilter(filterId) },
                )
            }
            items(categories, key = { "guide-cat-${it.remoteId}" }) { cat ->
                GuideFilterChip(
                    label = com.ultratv.tv.nativeapp.ui.common.prettyCategoryName(cat.name).uppercase(),
                    selected = selectedFilter == cat.remoteId,
                    onClick = { vm.selectFilter(cat.remoteId) },
                )
            }
        }

        // Time header row — sticky to the top of the right pane.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = T.EdgeGutter, end = T.EdgeGutter)
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left header — CHAÎNE label
            Text(
                "CHANNEL",
                color = T.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(200.dp),
            )
            Row(
                Modifier
                    .horizontalScroll(hScroll)
                    .height(38.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val slots = (0..23).toList()
                slots.forEach { slot ->
                    val slotMs = windowStart + slot * 30 * 60_000L
                    Box(
                        modifier = Modifier
                            .width((PX_PER_HOUR_DP / 2).dp)
                            .height(38.dp)
                            .background(if (slot % 2 == 0) T.Surface1 else androidx.compose.ui.graphics.Color.Transparent),
                    ) {
                        Text(
                            formatHm(slotMs),
                            color = T.Fg3,
                            fontSize = 11.sp,
                            fontFamily = F.Mono,
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.Line))

        if (channels.isEmpty()) {
            val emptyMessage = when {
                selectedFilter == FILTER_DEFAULT -> S.guideLoading
                selectedFilter == "FAVORITES" -> "No favorite channels yet. Add favorites from Live TV."
                selectedFilter == "ALL" -> S.guideNoChannels
                selectedFilter.startsWith(FILTER_MY_GROUP_PREFIX) ->
                    "No channels in this group. Add channels from Live TV."
                else -> "No channels in this category."
            }
            Text(
                emptyMessage,
                color = T.Fg3,
                modifier = Modifier.padding(start = T.EdgeGutter, top = 20.dp),
            )
        } else {
            LazyColumn(
                state = channelListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = T.EdgeGutter, end = T.EdgeGutter),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, c ->
                    GuideRow(
                        channel = c,
                        channelModifier = if (index == restoreFocusIndex) {
                            Modifier.focusRequester(channelFocusRequester)
                        } else {
                            Modifier
                        },
                        programmes = byChannel[c.id].orEmpty(),
                        windowStartMs = windowStart,
                        windowEndMs = windowEnd,
                        nowMs = now,
                        hScroll = hScroll,
                        onPlay = {
                            vm.playChannel(c, onPlayChannel)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideRow(
    channel: ChannelEntity,
    channelModifier: Modifier = Modifier,
    programmes: List<EpgEntity>,
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    hScroll: androidx.compose.foundation.ScrollState,
    onPlay: () -> Unit,
) {
    val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
    val F = com.ultratv.tv.nativeapp.ui.theme.UltraFonts
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .background(androidx.compose.ui.graphics.Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel column — fixed left: logo + name, clickable to start playback.
        Card(
            onClick = onPlay,
            modifier = channelModifier.width(200.dp).fillMaxHeight().padding(end = 8.dp),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            colors = com.ultratv.tv.nativeapp.ui.theme.ultraCardColors(
                containerColor = T.Surface1,
                focusedContainerColor = T.Surface2,
                focusedContentColor = T.Fg,
            ),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.ultratv.tv.nativeapp.ui.common.ChannelLogo(
                    name = channel.name,
                    logoUrl = channel.logo,
                    short = null,
                    hueSeed = channel.name.hashCode(),
                    hd = null,
                    size = 36.dp,
                    showBadge = false,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Text(
                    channel.name,
                    maxLines = 2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = T.Fg,
                )
            }
        }
        // Programme strip — scrolls horizontally in lock-step with the header.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(hScroll),
        ) {
            // Width = (window_end - window_start) / 1h * PX_PER_HOUR_DP
            val hours = ((windowEndMs - windowStartMs) / 3_600_000.0).coerceAtLeast(1.0)
            val totalWidthDp = (hours * PX_PER_HOUR_DP).toInt().dp
            Box(Modifier.width(totalWidthDp).fillMaxHeight()) {
                val visibleProgrammes = programmes
                    .filter { it.endMs > windowStartMs && it.startMs < windowEndMs }

                if (visibleProgrammes.isEmpty()) {
                    Card(
                        onClick = onPlay,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                            .width(300.dp)
                            .fillMaxHeight(),
                        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = com.ultratv.tv.nativeapp.ui.theme.ultraCardColors(
                            containerColor = T.Surface1,
                            focusedContainerColor = T.Surface2,
                            focusedContentColor = T.Fg,
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "No guide data",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = T.Fg2,
                            )
                            Text(
                                "Press REFRESH GUIDE above",
                                fontSize = 10.sp,
                                color = T.Fg4,
                            )
                        }
                    }
                }

                visibleProgrammes.forEach { prog ->
                        val start = prog.startMs.coerceAtLeast(windowStartMs)
                        val end = prog.endMs.coerceAtMost(windowEndMs)
                        val leftDp = ((start - windowStartMs) / 60_000f * PX_PER_MIN_DP).toInt().dp
                        val widthDp = ((end - start) / 60_000f * PX_PER_MIN_DP).toInt().coerceAtLeast(4).dp
                        val isLive = nowMs in prog.startMs..prog.endMs
                        Card(
                            onClick = onPlay,
                            modifier = Modifier
                                .padding(start = leftDp, top = 4.dp, bottom = 4.dp, end = 2.dp)
                                .width(widthDp)
                                .fillMaxHeight(),
                            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = if (isLive)
                                com.ultratv.tv.nativeapp.ui.theme.ultraCardColors(
                                    containerColor = T.AccentSoft,
                                    focusedContainerColor = T.Accent,
                                    focusedContentColor = androidx.compose.ui.graphics.Color.White,
                                )
                            else com.ultratv.tv.nativeapp.ui.theme.ultraCardColors(
                                containerColor = T.Surface1,
                                focusedContainerColor = T.Surface2,
                                focusedContentColor = T.Fg,
                            ),
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    prog.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isLive) T.Fg else T.Fg2,
                                    maxLines = 1,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formatHm(prog.startMs),
                                        fontSize = 10.sp,
                                        fontFamily = F.Mono,
                                        color = if (isLive) T.Accent else T.Fg4,
                                    )
                                    if (isLive) {
                                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                        Box(
                                            Modifier
                                                .width(5.dp)
                                                .height(5.dp)
                                                .background(T.Accent, androidx.compose.foundation.shape.CircleShape)
                                        )
                                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                        Text(
                                            com.ultratv.tv.nativeapp.i18n.LocalStrings.current.liveOnAirPill,
                                            color = T.Accent,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.6.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (isLive) {
                                    val duration = (prog.endMs - prog.startMs).coerceAtLeast(1L)
                                    val progress = ((nowMs - prog.startMs).toFloat() / duration.toFloat())
                                        .coerceIn(0f, 1f)
                                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .background(T.Line, RoundedCornerShape(2.dp)),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(progress)
                                                .height(3.dp)
                                                .background(T.Accent, RoundedCornerShape(2.dp)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                if (nowMs in windowStartMs..windowEndMs) {
                    val nowLeftDp = ((nowMs - windowStartMs) / 60_000f * PX_PER_MIN_DP).toInt().dp
                    Box(
                        Modifier
                            .padding(start = nowLeftDp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(T.Accent),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = com.ultratv.tv.nativeapp.ui.theme.ultraCardColors(
            containerColor = if (selected) T.AccentSoft else T.Surface1,
            focusedContainerColor = T.Accent,
            focusedContentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(
            label,
            color = if (selected) T.Fg else T.Fg3,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

private val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatHm(ms: Long): String = hmFmt.format(Date(ms))
