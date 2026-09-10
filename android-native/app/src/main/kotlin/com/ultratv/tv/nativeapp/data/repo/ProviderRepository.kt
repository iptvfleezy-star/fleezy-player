package com.ultratv.tv.nativeapp.data.repo

import com.ultratv.tv.nativeapp.data.db.CategoryDao
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.EpisodeDao
import com.ultratv.tv.nativeapp.data.db.MovieDao
import com.ultratv.tv.nativeapp.data.db.ProviderDao
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.db.SeriesDao
import com.ultratv.tv.nativeapp.data.m3u.M3uParser
import com.ultratv.tv.nativeapp.data.parental.ParentalStore
import com.ultratv.tv.nativeapp.data.stalker.StalkerClient
import com.ultratv.tv.nativeapp.data.xmltv.XmltvParser
import com.ultratv.tv.nativeapp.data.xtream.XtreamClient
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val xtream: XtreamClient,
    private val m3u: M3uParser,
    private val stalker: StalkerClient,
    private val parental: ParentalStore,
    private val syncStatus: SyncStatusBus,
    private val xmltv: XmltvParser,
    private val epgDao: com.ultratv.tv.nativeapp.data.db.EpgDao,
) {
    private val adultRegex = Regex("xxx|adult|18\\+|porn|ero|adulte|للكبار", RegexOption.IGNORE_CASE)

    // Chunk size for bulk inserts. Room flushes the WAL on each call so very
    // large lists (50k+ channels on big playlists) cause memory + I/O spikes;
    // chunking keeps RAM flat and lets the UI repaint between batches.
    private val INSERT_CHUNK = 500

    private suspend inline fun <T> insertChunked(items: List<T>, crossinline block: suspend (List<T>) -> Unit) {
        if (items.isEmpty()) return
        items.chunked(INSERT_CHUNK).forEach { block(it) }
    }
    fun observeProviders(): Flow<List<ProviderEntity>> = providerDao.observeAll()
    suspend fun firstActive(): ProviderEntity? = providerDao.firstActive()
    suspend fun byId(id: Long): ProviderEntity? = providerDao.byId(id)

    suspend fun addM3u(name: String, url: String): Long {
        providerDao.findByIdentity("M3U", url, "")?.let { return it.id }
        val pid = providerDao.upsert(
            ProviderEntity(
                name = name.ifBlank { runCatching { java.net.URI(url).host }.getOrNull() ?: "M3U" },
                kind = "M3U",
                baseUrl = url,
                username = "",
                password = "",
                active = false,    // explicit default is set in Settings; see setDefault
            ),
        )
        return pid
    }

    /**
     * Imports an M3U playlist from raw text (no HTTP fetch). Used by the local
     * file picker — caller reads the URI content via ContentResolver and hands
     * us the bytes. `baseUrl` is just stored as a hint, no fetching ever happens.
     */
    suspend fun addM3uFromText(name: String, label: String, text: String): Long {
        val pid = providerDao.upsert(
            ProviderEntity(
                name = name.ifBlank { label.ifBlank { "Local playlist" } },
                kind = "M3U_LOCAL",
                baseUrl = label,        // displayed-only; we never fetch this
                username = "",
                password = "",
                active = false,    // explicit default is set in Settings; see setDefault
            ),
        )
        val res = m3u.parse(text, pid)
        val pinSet = parental.isSet()
        val cats = if (!pinSet) res.categories
        else res.categories.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }
        categoryDao.deleteForProviderKind(pid, "LIVE")
        categoryDao.upsertAll(cats)
        channelDao.deleteForProvider(pid)
        channelDao.upsertAll(res.channels)
        return pid
    }

    /** Syncs an M3U provider. Channels-only (M3U has no movies/series schema). */
    suspend fun syncM3u(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        if (p.kind != "M3U") return 0
        fun step(s: String, pct: Int?) {
            onProgress(s); syncStatus.set(SyncStatusBus.Status(p.name, s, pct))
        }
        try {
            step("Downloading playlist…", 20)
            val res = m3u.fetch(p.baseUrl, p.id)
            step("Parsed ${res.channels.size} channels…", 60)
            val pinSet = parental.isSet()
            val cats = if (!pinSet) res.categories
            else res.categories.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }
            categoryDao.deleteForProviderKind(p.id, "LIVE")
            categoryDao.upsertAll(cats)
            channelDao.deleteForProvider(p.id)
            step("Saving ${res.channels.size} channels…", 90)
            insertChunked(res.channels) { channelDao.upsertAll(it) }
            step("Done — ${res.channels.size} channels", 100)
            return res.channels.size
        } finally {
            syncStatus.clear()
        }
    }

    suspend fun addStalker(name: String, portalUrl: String, mac: String): Long {
        val normalised = portalUrl.trimEnd('/')
        providerDao.findByIdentity("STALKER", normalised, mac.trim())?.let { return it.id }
        return providerDao.upsert(
            ProviderEntity(
                name = name.ifBlank { runCatching { java.net.URI(portalUrl).host }.getOrNull() ?: "Stalker" },
                kind = "STALKER",
                baseUrl = portalUrl.trimEnd('/'),
                username = mac.trim(),         // MAC address
                password = "",
                active = false,    // explicit default is set in Settings; see setDefault
            ),
        )
    }

    /** Syncs a Stalker portal. Channels-only — VOD/series are portal-specific add-ons. */
    suspend fun syncStalker(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        if (p.kind != "STALKER") return 0
        fun step(s: String, pct: Int?) {
            onProgress(s); syncStatus.set(SyncStatusBus.Status(p.name, s, pct))
        }
        try {
            step("Handshaking with portal…", 5)
            val s = stalker.handshake(p)

            val pinSet = parental.isSet()
            fun maybeLock(cats: List<CategoryEntity>): List<CategoryEntity> =
                if (!pinSet) cats
                else cats.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }

            step("Fetching live categories…", 10)
            val liveCats = stalker.fetchLiveCategories(p, s).let(::maybeLock)
            step("Fetching live channels…", 25)
            val chans = stalker.fetchLiveChannels(p, s)
            categoryDao.deleteForProviderKind(p.id, "LIVE")
            categoryDao.upsertAll(liveCats)
            channelDao.deleteForProvider(p.id)
            step("Saving ${chans.size} channels…", 40)
            insertChunked(chans) { channelDao.upsertAll(it) }

            step("Fetching VOD categories…", 55)
            val vodCats = stalker.fetchVodCategories(p, s).let(::maybeLock)
            step("Fetching VOD…", 65)
            val movies = stalker.fetchVodMovies(p, s)
            categoryDao.deleteForProviderKind(p.id, "MOVIE")
            categoryDao.upsertAll(vodCats)
            movieDao.deleteForProvider(p.id)
            step("Saving ${movies.size} movies…", 75)
            insertChunked(movies) { movieDao.upsertAll(it) }

            step("Fetching series categories…", 85)
            val serCats = stalker.fetchSeriesCategories(p, s).let(::maybeLock)
            step("Fetching series…", 90)
            val series = stalker.fetchSeries(p, s)
            categoryDao.deleteForProviderKind(p.id, "SERIES")
            categoryDao.upsertAll(serCats)
            seriesDao.deleteForProvider(p.id)
            step("Saving ${series.size} series…", 95)
            insertChunked(series) { seriesDao.upsertAll(it) }

            step("Done — ${chans.size} live · ${movies.size} VOD · ${series.size} series", 100)
            return chans.size + movies.size + series.size
        } finally {
            syncStatus.clear()
        }
    }

    // (Xtream path uses insertChunked(chans) above — see syncAll.)

    /**
     * Pulls the full xmltv feed for a provider and overwrites EPG for all its
     * channels. Channels are matched by [ChannelEntity.epgChannelId] (`tvg-id`
     * for M3U, `epg_channel_id` for Xtream) — channels without that field are
     * silently skipped (the older per-channel `get_short_epg` path still works
     * for those).
     */
    suspend fun syncXmltv(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        // Local M3U can't fetch xmltv. Stalker has its own EPG path (TODO).
        if (p.kind == "M3U_LOCAL" || p.kind == "STALKER") return 0
        fun step(s: String, pct: Int?) {
            onProgress(s); syncStatus.set(SyncStatusBus.Status(p.name, s, pct))
        }
        try {
            step("Fetching xmltv…", 10)
            // Build only the tiny (xmltv channel id → local channel id) projection
            // needed for matching. On very large providers this avoids materialising
            // full ChannelEntity rows containing logos, URLs, catch-up metadata, etc.
            val map = channelDao.epgKeysForProvider(providerId)
                .associate { it.epgChannelId to it.id }
            if (map.isEmpty()) {
                step("No xmltv channel IDs available for this provider", 100)
                return 0
            }
            step("Parsing xmltv (matching ${map.size} channels)…", 30)
            val programmes = xmltv.fetchAndParse(p, map)
            step("Saving ${programmes.size} programmes…", 75)
            epgDao.deleteForProvider(p.id)
            programmes.chunked(500).forEach { epgDao.upsertAll(it) }
            step("Done — ${programmes.size} programmes", 100)
            return programmes.size
        } catch (t: Throwable) {
            syncStatus.set(SyncStatusBus.Status(p.name, "EPG fetch failed: ${t.message}", null))
            return 0
        } finally {
            syncStatus.clear()
        }
    }

    /**
     * Resolves a stored stream URL into a playable URL. Only does work for
     * Stalker providers (URLs prefixed `stalker://`) — for everything else the
     * URL is already directly playable and we return it unchanged.
     */
    suspend fun resolvePlayUrl(channelId: Long, storedUrl: String): String {
        if (!storedUrl.startsWith("stalker://")) return storedUrl
        val ch = channelDao.byId(channelId) ?: return storedUrl
        val p = providerDao.byId(ch.providerId) ?: return storedUrl
        return runCatching { stalker.resolvePlayUrl(p, storedUrl) }.getOrElse { storedUrl }
    }

    /**
     * Same as [resolvePlayUrl] but indexed by providerId — used for movies /
     * episodes where we don't have a channel row to look up the provider on.
     */
    suspend fun resolveStalkerUrl(providerId: Long, storedUrl: String): String {
        if (!storedUrl.startsWith("stalker://")) return storedUrl
        val p = providerDao.byId(providerId) ?: return storedUrl
        return runCatching { stalker.resolvePlayUrl(p, storedUrl) }.getOrElse { storedUrl }
    }

    suspend fun addXtream(name: String, baseUrl: String, username: String, password: String): Long {
        val normalised = baseUrl.trimEnd('/')
        val cleanUser = username.trim()
        val existing = providerDao.findByIdentity("XTREAM", normalised, cleanUser)
        val candidate = if (existing != null) {
            existing.copy(
                name = name.ifBlank { existing.name },
                password = password,
            )
        } else {
            ProviderEntity(
                name = name.ifBlank { runCatching { java.net.URI(normalised).host }.getOrNull() ?: "Fleezy" },
                kind = "XTREAM",
                baseUrl = normalised,
                username = cleanUser,
                password = password,
                active = false,
            )
        }

        // Validate before touching the database. A typo must never overwrite
        // a previously working password or create a ghost provider record.
        xtream.validateCredentials(candidate)

        if (existing != null) {
            providerDao.upsert(candidate)
            return existing.id
        }
        return providerDao.upsert(candidate)
    }

    /**
     * Marks one provider as the default and deactivates the others. Every
     * screen that picks "the current provider" uses `firstOrNull { it.active }`,
     * so setting default = id atomically switches the whole app to that
     * provider's catalog.
     */
    suspend fun setDefault(id: Long) {
        providerDao.deactivateAll()
        providerDao.activate(id)
    }

    suspend fun delete(id: Long) {
        channelDao.deleteForProvider(id)
        movieDao.deleteForProvider(id)
        seriesDao.deleteForProvider(id)
        categoryDao.deleteForProviderKind(id, "LIVE")
        categoryDao.deleteForProviderKind(id, "MOVIE")
        categoryDao.deleteForProviderKind(id, "SERIES")
        providerDao.delete(id)
    }

    /**
     * Fast first-login sync for Fleezy Xtream accounts. Loads only Live TV so
     * the customer can enter the app quickly; Movies/Series can continue after
     * the onboarding overlay is gone.
     */
    suspend fun syncXtreamLiveOnly(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        if (p.kind != "XTREAM") return 0

        fun step(label: String, pct: Int? = null) {
            onProgress(label)
            syncStatus.set(SyncStatusBus.Status(provider = p.name, step = label, percent = pct))
        }

        val pinSet = parental.isSet()
        fun maybeLock(cats: List<CategoryEntity>): List<CategoryEntity> =
            if (!pinSet) cats else cats.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }

        try {
            // Credentials were already validated by addXtream() immediately
            // before this first-login sync. Avoid a duplicate back-to-back
            // player_api.php auth request before loading Live TV.
            step("Live categories…", 20)
            val liveCats = xtream.fetchLiveCategories(p).let(::maybeLock)
            step("Live channels…", 45)
            val chans = xtream.fetchLiveStreams(p)

            categoryDao.deleteForProviderKind(p.id, "LIVE")
            categoryDao.upsertAll(liveCats)
            channelDao.deleteForProvider(p.id)
            step("Saving ${chans.size} live channels…", 75)
            insertChunked(chans) { channelDao.upsertAll(it) }

            step("Live TV ready — ${chans.size} channels", 100)
            return chans.size
        } finally {
            syncStatus.clear()
        }
    }

    /**
     * Completes the heavy Xtream catalog after Live TV is usable.
     * A failure here must not invalidate an otherwise working Fleezy login.
     */
    suspend fun syncXtreamLibraryOnly(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        if (p.kind != "XTREAM") return 0

        fun step(label: String, pct: Int? = null) {
            onProgress(label)
            syncStatus.set(SyncStatusBus.Status(provider = p.name, step = label, percent = pct))
        }

        val pinSet = parental.isSet()
        fun maybeLock(cats: List<CategoryEntity>): List<CategoryEntity> =
            if (!pinSet) cats else cats.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }

        try {
            step("Loading Movies…", 10)
            val movCats = xtream.fetchVodCategories(p).let(::maybeLock)
            val movs = xtream.fetchVodStreams(p)
            categoryDao.deleteForProviderKind(p.id, "MOVIE")
            categoryDao.upsertAll(movCats)
            movieDao.deleteForProvider(p.id)
            step("Saving ${movs.size} movies…", 45)
            insertChunked(movs) { movieDao.upsertAll(it) }

            step("Loading Series…", 60)
            val serCats = xtream.fetchSeriesCategories(p).let(::maybeLock)
            val series = xtream.fetchSeries(p)
            categoryDao.deleteForProviderKind(p.id, "SERIES")
            categoryDao.upsertAll(serCats)
            seriesDao.deleteForProvider(p.id)
            step("Saving ${series.size} series…", 90)
            insertChunked(series) { seriesDao.upsertAll(it) }

            step("Library ready — ${movs.size} movies · ${series.size} series", 100)
            return movs.size + series.size
        } finally {
            syncStatus.clear()
        }
    }

    /**
     * Pulls Live + VOD + Series catalogs. Returns total item count.
     * Note: episodes are loaded on-demand when the user opens a series.
     */
    suspend fun syncAll(providerId: Long, onProgress: (String) -> Unit = {}): Int {
        val p = providerDao.byId(providerId) ?: return 0
        // Local M3U is parsed once at import — re-syncing requires picking the file again.
        if (p.kind == "M3U_LOCAL") return channelDao.count(p.id)
        if (p.kind == "M3U") return syncM3u(providerId, onProgress)
        if (p.kind == "STALKER") return syncStalker(providerId, onProgress)

        // Wrapper that pushes to both the user callback AND the global sync bus
        // so a banner can show progress on any screen.
        fun step(label: String, pct: Int? = null) {
            onProgress(label)
            syncStatus.set(SyncStatusBus.Status(provider = p.name, step = label, percent = pct))
        }

        val pinSet = parental.isSet()
        fun maybeLock(cats: List<CategoryEntity>): List<CategoryEntity> =
            if (!pinSet) cats
            else cats.map { it.copy(locked = adultRegex.containsMatchIn(it.name)) }

        try {
            step("Checking account…", 2)
            xtream.validateCredentials(p)

            step("Live categories…", 5)
            val liveCats = xtream.fetchLiveCategories(p).let(::maybeLock)
            step("Live channels…", 15)
            val chans = xtream.fetchLiveStreams(p)
            categoryDao.deleteForProviderKind(p.id, "LIVE")
            categoryDao.upsertAll(liveCats)
            channelDao.deleteForProvider(p.id)
            step("Saving ${chans.size} channels…", 25)
            insertChunked(chans) { channelDao.upsertAll(it) }

            step("Movie categories…", 35)
            val movCats = xtream.fetchVodCategories(p).let(::maybeLock)
            step("Movies (${movCats.size} categories)…", 45)
            val movs = xtream.fetchVodStreams(p)
            categoryDao.deleteForProviderKind(p.id, "MOVIE")
            categoryDao.upsertAll(movCats)
            movieDao.deleteForProvider(p.id)
            step("Saving ${movs.size} movies…", 55)
            insertChunked(movs) { movieDao.upsertAll(it) }

            step("Series categories…", 70)
            val serCats = xtream.fetchSeriesCategories(p).let(::maybeLock)
            step("Series…", 80)
            val series = xtream.fetchSeries(p)
            categoryDao.deleteForProviderKind(p.id, "SERIES")
            categoryDao.upsertAll(serCats)
            seriesDao.deleteForProvider(p.id)
            step("Saving ${series.size} series…", 95)
            insertChunked(series) { seriesDao.upsertAll(it) }

            step("Done — ${chans.size} live · ${movs.size} movies · ${series.size} series", 100)
            return chans.size + movs.size + series.size
        } finally {
            syncStatus.clear()
        }
    }
}
