package com.ultratv.tv.nativeapp.ui.player

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ultratv.tv.nativeapp.data.repo.HistoryRepository
import com.ultratv.tv.nativeapp.data.repo.PlaybackContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackContext,
    private val history: HistoryRepository,
    private val zapQueue: com.ultratv.tv.nativeapp.data.repo.LivePlaybackQueue,
    private val provider: com.ultratv.tv.nativeapp.data.repo.ProviderRepository,
    private val epgDao: com.ultratv.tv.nativeapp.data.db.EpgDao,
    private val recordings: com.ultratv.tv.nativeapp.data.recording.RecordingRepository,
    private val prefs: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
    private val channelDao: com.ultratv.tv.nativeapp.data.db.ChannelDao,
) : ViewModel() {

    /** Snapshot of the playback knobs the screen reads at construction time. */
    suspend fun playbackPrefs(): com.ultratv.tv.nativeapp.data.prefs.UserPrefs =
        prefs.flow.first()

    /**
     * Resolves a catchup URL for [prog] on the channel currently set in the
     * PlaybackContext and starts playing it. Used by EPG rows in the past on
     * channels that report catchup support.
     */
    fun playCatchup(prog: com.ultratv.tv.nativeapp.data.db.EpgEntity, onReady: (url: String, title: String) -> Unit) {
        viewModelScope.launch {
            val cur = current.value ?: return@launch
            val ch = channelDao.byRemoteId(cur.providerId, cur.remoteId) ?: return@launch
            val url = com.ultratv.tv.nativeapp.data.repo.Catchup.buildUrl(ch, prog) ?: return@launch
            val title = "${ch.name} — ${prog.title}"
            zapQueue.clear()
            playback.set(
                PlaybackContext.Item(
                    providerId = ch.providerId,
                    kind = "CATCHUP",
                    remoteId = "${ch.remoteId}:${prog.startMs}",
                    title = title,
                    poster = ch.logo,
                    streamUrl = url,
                    parentRemoteId = ch.remoteId,
                )
            )
            onReady(url, title)
        }
    }

    val current: StateFlow<PlaybackContext.Item?> = playback.current

    /** Queues a Live channel recording for `maxMinutes`. HLS m3u8 → segment
     *  recorder; non-HLS live → single HTTP body grab (won't capture more than
     *  what the server already buffered). */
    fun recordLive(maxMinutes: Int = 120, toastTemplate: String = "Recording queued (max %1\$d min)") {
        val c = playback.current.value ?: return
        if (c.kind != "LIVE") return
        viewModelScope.launch {
            recordings.enqueue(c.providerId, "LIVE", c.remoteId, c.title, c.streamUrl, maxMinutes)
            com.ultratv.tv.nativeapp.ui.common.Toaster.ok(toastTemplate.format(maxMinutes))
        }
    }

    /** Resolves the next/previous channel in the active zap queue (Live only)
     *  and updates [PlaybackContext] so the player swaps stream URL. Returns
     *  the new URL or null when there's nothing queued. */
    suspend fun zap(forward: Boolean): String? {
        val state = zapQueue.state.value ?: return null
        val target = zapQueue.adjacent(forward) ?: return null
        val resolved = runCatching {
            provider.resolvePlayUrl(target.id, target.streamUrl)
        }.getOrElse {
            com.ultratv.tv.nativeapp.ui.common.Toaster.show("Unable to open channel. Try again.")
            return null
        }
        zapQueue.set(state.channels, target)
        playback.set(PlaybackContext.Item(
            providerId = target.providerId, kind = "LIVE", remoteId = target.remoteId,
            title = target.name, poster = target.logo, streamUrl = resolved,
        ))
        return resolved
    }

    data class DrawerState(
        val channels: List<com.ultratv.tv.nativeapp.data.db.ChannelEntity>,
        val index: Int,
        val nowNext: Map<Long, Pair<com.ultratv.tv.nativeapp.data.db.EpgEntity?, com.ultratv.tv.nativeapp.data.db.EpgEntity?>>,
    )

    val queue: StateFlow<DrawerState?> = zapQueue.state.map { s ->
        if (s == null) null
        else {
            val now = System.currentTimeMillis()
            val epgFrom = (s.index - 100).coerceAtLeast(0)
            val epgTo = (s.index + 201).coerceAtMost(s.channels.size)
            val ids = s.channels.subList(epgFrom, epgTo).map { it.id }
            val rows = ids.chunked(500).flatMap { chunk ->
                epgDao.rangeForChannels(chunk, now - 30 * 60_000, now + 4 * 60 * 60_000)
            }
            val byCh = rows.groupBy { it.channelId }
            val metadata = ids.associateWith { id ->
                val list = byCh[id].orEmpty()
                val current = list.firstOrNull { it.startMs <= now && it.endMs > now }
                val next = list.firstOrNull { it.startMs > now }
                current to next
            }
            DrawerState(
                channels = s.channels,
                index = s.index,
                nowNext = metadata,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun zapTo(channel: com.ultratv.tv.nativeapp.data.db.ChannelEntity): String? {
        val s = zapQueue.state.value ?: return null
        val idx = s.channels.indexOfFirst { it.id == channel.id }
        if (idx < 0) return null
        val resolved = runCatching {
            provider.resolvePlayUrl(channel.id, channel.streamUrl)
        }.getOrElse {
            com.ultratv.tv.nativeapp.ui.common.Toaster.show("Unable to open channel. Try again.")
            return null
        }
        zapQueue.set(s.channels, channel)
        playback.set(PlaybackContext.Item(
            providerId = channel.providerId, kind = "LIVE", remoteId = channel.remoteId,
            title = channel.name, poster = channel.logo, streamUrl = resolved,
        ))
        return resolved
    }

    suspend fun prepareResume(): Long {
        val c = playback.current.value ?: return 0L
        if (c.kind == "LIVE") return 0L
        return history.resumePositionMs(c.providerId, c.kind, c.remoteId)
    }

    suspend fun retryCurrentStream(): String? {
        val item = playback.current.value ?: return null
        if (item.kind != "LIVE") return item.streamUrl

        val channel = channelDao.byRemoteId(item.providerId, item.remoteId)
            ?: return item.streamUrl
        val resolved = runCatching {
            provider.resolvePlayUrl(channel.id, channel.streamUrl)
        }.getOrElse {
            item.streamUrl
        }
        playback.set(
            item.copy(
                title = channel.name,
                poster = channel.logo,
                streamUrl = resolved,
            )
        )
        return resolved
    }

    fun recordProgress(positionMs: Long, durationMs: Long) {
        val c = playback.current.value ?: return
        if (positionMs < 5_000 && c.kind != "LIVE") return
        viewModelScope.launch {
            history.record(
                providerId = c.providerId,
                kind = c.kind,
                remoteId = c.remoteId,
                title = c.title,
                poster = c.poster,
                streamUrl = c.streamUrl,
                positionMs = if (c.kind == "LIVE") 0 else positionMs,
                durationMs = if (c.kind == "LIVE") 0 else durationMs,
                parentRemoteId = c.parentRemoteId,
            )
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playbackItem by vm.current.collectAsState()
    val isLive = playbackItem?.kind == "LIVE"
    var currentUrl by remember { mutableStateOf(url) }
    var currentTitle by remember { mutableStateOf(title) }
    var tracksOpen by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }
    var aspectMode by remember(isLive) {
        mutableStateOf(if (isLive) AspectMode.Zoom else AspectMode.Fit)
    }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var chromeVisible by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var decoderName by remember { mutableStateOf("—") }
    var droppedFramesTotal by remember { mutableIntStateOf(0) }
    var displayModeLabel by remember { mutableStateOf("—") }
    var measuredFrameRate by remember { mutableFloatStateOf(0f) }
    val cadenceEstimator = remember { LiveCadenceEstimator() }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    BackHandler {
        when {
            tracksOpen -> tracksOpen = false
            displayMenu -> displayMenu = false
            statsOpen -> statsOpen = false
            drawerOpen -> drawerOpen = false
            chromeVisible -> chromeVisible = false
            else -> onBack()
        }
    }

    val loadedPrefs by androidx.compose.runtime.produceState<com.ultratv.tv.nativeapp.data.prefs.UserPrefs?>(
        initialValue = null,
    ) {
        value = vm.playbackPrefs()
    }
    val playbackPrefs = loadedPrefs ?: run {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalPreferredModeId = activity?.window?.attributes?.preferredDisplayModeId ?: 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { window ->
                val lp = window.attributes
                if (lp.preferredDisplayModeId != originalPreferredModeId) {
                    lp.preferredDisplayModeId = originalPreferredModeId
                    window.attributes = lp
                }
            }
        }
    }

    val player = remember {
        val bufMs = (playbackPrefs.bufferSeconds * 1000).coerceAtLeast(5_000)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5_000,
                bufMs,
                500,
                1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val rtmpFactory = androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
        val routingFactory = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var inner: androidx.media3.datasource.DataSource? = null
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    val scheme = spec.uri.scheme?.lowercase()
                    val backing = if (scheme == "rtmp" || scheme == "rtmps")
                        rtmpFactory.createDataSource() else defaultFactory.createDataSource()
                    listeners.forEach { backing.addTransferListener(it) }
                    inner = backing
                    return backing.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    inner?.read(buffer, offset, length) ?: -1
                override fun getUri(): android.net.Uri? = inner?.uri
                override fun close() {
                    runCatching { inner?.close() }
                    inner = null
                }
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    listeners += transferListener
                    inner?.addTransferListener(transferListener)
                }
                override fun getResponseHeaders(): Map<String, List<String>> =
                    inner?.responseHeaders ?: emptyMap()
            }
        }
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(routingFactory)
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            setEnableDecoderFallback(true)
            // Fire TV MediaCodec implementations vary by model/Fire OS build.
            // Synchronous queueing avoids vendor async-queue corruption while
            // preserving hardware decoding. This is deliberately Amazon-only.
            if (android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) {
                forceDisableMediaCodecAsynchronousQueueing()
            }
        }
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
            playWhenReady = true
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setForceHighestSupportedBitrate(true)
                .build()
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playbackError = when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Network connection failed"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                            "The stream server rejected the request"
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                            "This stream could not be decoded on this device"
                        else -> "Playback failed"
                    }
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val act = (context as? android.app.Activity) ?: return
                    val display = act.windowManager.defaultDisplay ?: return
                    displayModeLabel = formatDisplayMode(display.mode)
                    if (!playbackPrefs.autoFrameRate) return

                    val fmt = currentTracks.groups
                        .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                        ?.let { g ->
                            (0 until g.length)
                                .firstOrNull { g.isTrackSelected(it) }
                                ?.let { g.getTrackFormat(it) }
                        }
                    val fps = fmt?.frameRate?.takeIf { it > 0f }
                        ?: measuredFrameRate.takeIf { it > 0f }
                        ?: return
                    displayModeLabel = applyCadenceMode(act, normalizeBroadcastRate(fps))
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        playbackError = null
                    }
                }
            })
            addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    decoderNameValue: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoderName = decoderNameValue
                }

                override fun onDroppedVideoFrames(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    droppedFramesTotal += droppedFrames
                }
            })
            setVideoFrameMetadataListener { presentationTimeUs, _, format, mediaFormat ->
                if (!isLive) return@setVideoFrameMetadataListener

                val formatRate = format.frameRate.takeIf { it > 0f }
                val mediaFormatRate = mediaFormat?.let { mf ->
                    runCatching {
                        mf.getFloat(android.media.MediaFormat.KEY_FRAME_RATE)
                    }.getOrNull()?.takeIf { it > 0f }
                        ?: runCatching {
                            mf.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toFloat()
                        }.getOrNull()?.takeIf { it > 0f }
                }
                val inferredRate = cadenceEstimator.sample(presentationTimeUs)
                val resolved = formatRate ?: mediaFormatRate ?: inferredRate
                if (resolved != null && resolved > 0f) {
                    val normalized = normalizeBroadcastRate(resolved)
                    if (kotlin.math.abs(normalized - measuredFrameRate) > 0.05f) {
                        measuredFrameRate = normalized
                        if (playbackPrefs.autoFrameRate) {
                            (context as? Activity)?.let { act ->
                                displayModeLabel = applyCadenceMode(act, normalized)
                            }
                        }
                    }
                }
            }
        }
    }

    var stats by remember { mutableStateOf(StreamStats()) }
    LaunchedEffect(statsOpen, isLive, chromeVisible, decoderName, droppedFramesTotal, displayModeLabel, measuredFrameRate) {
        if (!statsOpen && !(isLive && chromeVisible)) return@LaunchedEffect
        while (true) {
            stats = StreamStats.read(
                player = player,
                decoderName = decoderName,
                droppedFrames = droppedFramesTotal,
                displayMode = displayModeLabel,
                measuredFrameRate = measuredFrameRate,
            )
            delay(1_000)
        }
    }

    LaunchedEffect(chromeVisible, displayMenu, statsOpen, tracksOpen, drawerOpen) {
        if (chromeVisible && !displayMenu && !statsOpen && !tracksOpen && !drawerOpen) {
            delay(4_000)
            chromeVisible = false
        }
    }

    var sleepDeadlineMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepDeadlineMs) {
        if (sleepDeadlineMs <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepDeadlineMs) delay(5_000)
        player.pause()
        com.ultratv.tv.nativeapp.ui.common.Toaster.show(S.sleepReached)
        onBack()
    }
    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank()) {
            playbackError = null
            decoderName = "—"
            droppedFramesTotal = 0
            measuredFrameRate = 0f
            cadenceEstimator.reset()
            if (isLive && player.mediaItemCount > 0) {
                player.stop()
                player.clearMediaItems()
            }
            player.setMediaItem(MediaItem.fromUri(currentUrl))
            player.prepare()
            val resume = vm.prepareResume()
            if (resume > 5_000) {
                player.seekTo(resume)
            }
            player.play()
        }
    }
    LaunchedEffect(playbackSpeed) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed)
    }

    LaunchedEffect(player) {
        while (true) {
            delay(10_000)
            if (player.duration > 0) {
                vm.recordProgress(player.currentPosition, player.duration)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.recordProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            player.release()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (!isLive || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (drawerOpen || tracksOpen || displayMenu || statsOpen) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> {
                        scope.launch {
                            vm.zap(forward = false)?.let {
                                currentUrl = it
                                currentTitle = vm.current.value?.title ?: currentTitle
                                chromeVisible = true
                            }
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        scope.launch {
                            vm.zap(forward = true)?.let {
                                currentUrl = it
                                currentTitle = vm.current.value?.title ?: currentTitle
                                chromeVisible = true
                            }
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        drawerOpen = true
                        chromeVisible = false
                        true
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        chromeVisible = !chromeVisible
                        true
                    }
                    Key.DirectionRight -> {
                        statsOpen = true
                        chromeVisible = false
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(
                    com.ultratv.tv.nativeapp.R.layout.fleezy_player_view,
                    null,
                    false,
                ) as PlayerView).apply {
                    this.player = player
                    useController = !isLive
                    setShowFastForwardButton(!isLive)
                    setShowRewindButton(!isLive)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    controllerShowTimeoutMs = if (isLive) 0 else 3000
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { v ->
                v.resizeMode = aspectMode.resizeMode
                v.useController = !isLive
            },
            modifier = Modifier.fillMaxSize(),
        )

        val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        val activity = remember(context) { context as? Activity }
        val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        var brightness by remember {
            mutableFloatStateOf(
                activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
            )
        }
        var gestureLabel by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(gestureLabel) {
            if (gestureLabel != null) { delay(900); gestureLabel = null }
        }
        var volAccum by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.align(Alignment.CenterEnd).width(120.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { volAccum = 0f },
                    ) { _, dragAmount ->
                        volAccum += -dragAmount
                        val step = (volAccum / 60f).toInt()
                        if (step != 0) {
                            volume = (volume + step).coerceIn(0, maxVol)
                            volAccum -= step * 60f
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                            gestureLabel = "🔊 ${(volume * 100 / maxVol)}%"
                        }
                    }
                },
        )
        var brAccum by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.align(Alignment.CenterStart).width(120.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { brAccum = 0f },
                    ) { _, dragAmount ->
                        brAccum += -dragAmount
                        val deltaPct = (brAccum / 8f).toInt()
                        if (deltaPct != 0) {
                            brightness = (brightness + deltaPct / 100f).coerceIn(0.05f, 1f)
                            brAccum -= deltaPct * 8f
                            activity?.window?.let { w ->
                                val attrs = w.attributes as WindowManager.LayoutParams
                                attrs.screenBrightness = brightness
                                w.attributes = attrs
                            }
                            gestureLabel = "☀ ${(brightness * 100).toInt()}%"
                        }
                    }
                },
        )
        gestureLabel?.let { lbl ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(lbl, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (!isLive || chromeVisible) {
            Row(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                Column {
                    Text(currentTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (isLive) {
                        val quality = listOf(
                            stats.resolution,
                            stats.frameRate,
                            stats.videoCodec,
                            stats.videoBitrate,
                        ).filter { it != "—" }.joinToString(" · ")
                        if (quality.isNotBlank()) {
                            Text(
                                quality,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                            )
                        }
                        Text(
                            "OK info · RIGHT stats · LEFT channels · ▲ ▼ change channel · BACK exit",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        playbackError?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(min = 320.dp, max = 520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6111318))
                    .border(1.dp, Color(0xFF30343D), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    message,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Check the connection or try the stream again.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                )
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = {
                        scope.launch {
                            playbackError = null
                            val retryUrl = vm.retryCurrentStream() ?: currentUrl
                            currentUrl = retryUrl
                            player.stop()
                            player.clearMediaItems()
                            player.setMediaItem(MediaItem.fromUri(retryUrl))
                            player.prepare()
                            player.play()
                        }
                    }) {
                        Text("Retry")
                    }
                    Button(onClick = onBack) {
                        Text("Exit")
                    }
                }
            }
        }

        if (!isLive) {
            FlowRow(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .widthIn(max = 760.dp)
                    .padding(24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4,
            ) {
                var sleepMenu by remember { mutableStateOf(false) }
                Button(onClick = { sleepMenu = !sleepMenu }) {
                    Text(
                        if (sleepDeadlineMs > 0L) {
                            val mins = ((sleepDeadlineMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
                            "💤 ${mins}min"
                        } else "💤 " + S.sleepLabel
                    )
                }
                if (sleepMenu) {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        SleepOption(S.sleepMin15) { sleepDeadlineMs = System.currentTimeMillis() + 15 * 60_000; sleepMenu = false }
                        SleepOption(S.sleepMin30) { sleepDeadlineMs = System.currentTimeMillis() + 30 * 60_000; sleepMenu = false }
                        SleepOption(S.sleep1h) { sleepDeadlineMs = System.currentTimeMillis() + 60 * 60_000; sleepMenu = false }
                        SleepOption(S.sleep2h) { sleepDeadlineMs = System.currentTimeMillis() + 120 * 60_000; sleepMenu = false }
                        if (sleepDeadlineMs > 0L) {
                            SleepOption(S.sleepCancel) { sleepDeadlineMs = 0L; sleepMenu = false }
                        }
                    }
                }
                Button(onClick = { tracksOpen = true }) { Text("🎚 ${S.playerTracks}") }
                Button(onClick = { displayMenu = !displayMenu }) { Text("📐 ${S.playerDisplay}") }
                Button(onClick = { statsOpen = !statsOpen }) {
                    Text("📊 " + S.playerStats)
                }
                Button(onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(currentUrl), "video/*")
                            putExtra("title", currentTitle)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(Intent.createChooser(intent, S.recordingsOpenWith))
                    }
                }) { Text(S.playerExternal) }
            }
        }
        if (displayMenu) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 70.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                Text(S.playerAspect, color = Color(0xFF66B3FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                AspectMode.entries.forEach { mode ->
                    Button(
                        onClick = { aspectMode = mode; displayMenu = false },
                        colors = if (mode == aspectMode) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { Text(mode.label, fontSize = 12.sp) }
                }
                if (!isLive) {
                    Text(S.playerSpeed, color = Color(0xFF66B3FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        Button(
                            onClick = { playbackSpeed = sp; displayMenu = false },
                            colors = if (sp == playbackSpeed) androidx.tv.material3.ButtonDefaults.colors()
                            else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                        ) { Text("${sp}x", fontSize = 12.sp) }
                    }
                }
            }
        }
        if (drawerOpen && isLive) {
            LiveDrawer(
                vm = vm,
                onPick = { ch ->
                    scope.launch {
                        vm.zapTo(ch)?.let {
                            currentUrl = it
                            currentTitle = vm.current.value?.title ?: currentTitle
                        }
                        drawerOpen = false
                    }
                },
                onDismiss = { drawerOpen = false },
            )
        }
        if (tracksOpen) {
            TracksDialog(player = player, onDismiss = { tracksOpen = false })
        }
        if (statsOpen) {
            val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 40.dp)
                    .width(330.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color(0xD0000000))
                    .border(1.dp, T.Line2, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "STREAM STATS",
                        color = T.Fg3,
                        fontSize = 10.sp,
                        letterSpacing = 2.3.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isLive) {
                        Text("BACK to close", color = T.Fg3, fontSize = 9.sp)
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                StatRow(S.statResolution, stats.resolution)
                StatRow(S.statVideoCodec, stats.videoCodec)
                StatRow(S.statFrameRate, stats.frameRate)
                StatRow(S.statVideoBitrate, stats.videoBitrate)
                StatRow("Decoder", stats.decoder)
                StatRow("TV output", stats.displayMode)
                StatRow(S.statAudioCodec, stats.audioCodec)
                StatRow(S.statAudioChannels, stats.audioChannels)
                StatRow(S.statBuffered, stats.bufferedAhead)
                StatRow(S.statDroppedFrames, stats.droppedFrames)
            }
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun TracksDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks = player.currentTracks
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(androidx.tv.material3.MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            androidx.tv.material3.Text(
                "🎚 " + S.playerTracks,
                color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            val audioGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            androidx.tv.material3.Text(S.playerAudioTemplate.format(audioGroups.sumOf { it.length }), color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            audioGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val label = listOfNotNull(
                        fmt.label,
                        fmt.language,
                        fmt.sampleMimeType?.removePrefix("audio/"),
                        fmt.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                    ).joinToString(" · ").ifBlank { "Track ${i + 1}" }
                    androidx.tv.material3.Button(
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            onDismiss()
                        },
                        colors = if (group.isTrackSelected(i)) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { androidx.tv.material3.Text(label, fontSize = 13.sp) }
                }
            }
            val subGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            androidx.tv.material3.Text(S.playerSubtitlesTemplate.format(subGroups.sumOf { it.length }), color = androidx.tv.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            androidx.tv.material3.Button(
                onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                        .build()
                    onDismiss()
                },
                colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
            ) { androidx.tv.material3.Text(S.playerOff, fontSize = 13.sp) }
            subGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val label = listOfNotNull(fmt.label, fmt.language).joinToString(" · ").ifBlank { "Subtitle ${i + 1}" }
                    androidx.tv.material3.Button(
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            onDismiss()
                        },
                        colors = if (group.isTrackSelected(i)) androidx.tv.material3.ButtonDefaults.colors()
                        else androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant),
                    ) { androidx.tv.material3.Text(label, fontSize = 13.sp) }
                }
            }
            androidx.tv.material3.Button(
                onClick = onDismiss,
                colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = androidx.tv.material3.MaterialTheme.colorScheme.background),
            ) { androidx.tv.material3.Text(S.close) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.width(100.dp))
        Text(value, color = Color.White, fontSize = 11.sp)
    }
}

private enum class AspectMode(val label: String, val resizeMode: Int) {
    Fit("Fit", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("Fill", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("Zoom", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FixedWidth("16:9", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    FixedHeight("4:3", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
}

private data class StreamStats(
    val resolution: String = "—",
    val videoCodec: String = "—",
    val frameRate: String = "—",
    val videoBitrate: String = "—",
    val decoder: String = "—",
    val displayMode: String = "—",
    val audioCodec: String = "—",
    val audioChannels: String = "—",
    val bufferedAhead: String = "—",
    val droppedFrames: String = "—",
) {
    companion object {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun read(
            player: ExoPlayer,
            decoderName: String,
            droppedFrames: Int,
            displayMode: String,
            measuredFrameRate: Float,
        ): StreamStats {
            val v = player.videoFormat
            val a = player.audioFormat
            val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
            val codec = listOfNotNull(
                v?.sampleMimeType?.removePrefix("video/"),
                v?.codecs?.takeIf { it.isNotBlank() },
            ).distinct().joinToString(" / ").ifBlank { "—" }
            val fps = v?.frameRate?.takeIf { it > 0f }
                ?: measuredFrameRate.takeIf { it > 0f }
            return StreamStats(
                resolution = v?.let { "${it.width}×${it.height}" } ?: "—",
                videoCodec = codec,
                frameRate = fps?.let { "%.2f fps".format(it) } ?: "—",
                videoBitrate = v?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—",
                decoder = decoderName,
                displayMode = displayMode,
                audioCodec = a?.sampleMimeType?.removePrefix("audio/") ?: "—",
                audioChannels = a?.channelCount?.takeIf { it > 0 }?.toString() ?: "—",
                bufferedAhead = "${bufferedMs / 1000}s",
                droppedFrames = droppedFrames.toString(),
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun SleepOption(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(vertical = 2.dp),
    ) { Text(label, fontSize = 13.sp) }
}
