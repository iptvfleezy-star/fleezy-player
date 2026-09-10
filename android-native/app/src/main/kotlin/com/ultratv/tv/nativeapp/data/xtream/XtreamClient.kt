package com.ultratv.tv.nativeapp.data.xtream

import android.os.SystemClock
import android.util.Log
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.db.EpgEntity
import com.ultratv.tv.nativeapp.data.db.EpisodeEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.db.SeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Xtream-Codes player_api.php client. Endpoints used:
 *   action= get_live_categories | get_live_streams
 *           get_vod_categories  | get_vod_streams | get_vod_info
 *           get_series_categories | get_series   | get_series_info
 *           get_short_epg (channel-level EPG)
 *
 * Stream URLs:
 *   Live:    {base}/live/{user}/{pass}/{stream_id}.ts
 *   Movies:  {base}/movie/{user}/{pass}/{stream_id}.{container_extension}
 *   Episode: {base}/series/{user}/{pass}/{episode_id}.{container_extension}
 */
@Singleton
class XtreamClient @Inject constructor(private val ok: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ---- Authentication ----

    /**
     * Validates Xtream credentials before any catalog sync. Xtream servers
     * normally return a user_info object with auth=1 for valid accounts.
     * Throwing here lets onboarding stay on the sign-in screen instead of
     * accepting bad credentials and showing an empty library.
     */
    suspend fun validateCredentials(p: ProviderEntity) {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=get_account_info")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: error("Invalid response from Fleezy server")
        val info = root["user_info"] as? JsonObject
            ?: error("Invalid username or password")
        val auth = info["auth"]?.str()
        if (auth != "1") error("Invalid username or password")

        val status = info["status"]?.str()
        if (!status.isNullOrBlank() && !status.equals("Active", ignoreCase = true)) {
            error("Account status: $status")
        }
    }

    // ---- Live ----

    suspend fun fetchLiveCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_live_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "LIVE", remoteId = rid, name = name)
    }

    suspend fun fetchLiveStreams(p: ProviderEntity): List<ChannelEntity> =
        fetchLiveStreamsInternal(p, categoryRemoteId = null)

    /**
     * Category-scoped Live fetch for large Xtream/IPTV Boss lineups.
     *
     * The existing full-catalog path remains the default for now. This method
     * gives the repository a safe path to move to category-first/on-demand
     * loading later without changing the customer-facing Xtream contract.
     */
    suspend fun fetchLiveStreamsForCategory(
        p: ProviderEntity,
        categoryRemoteId: String,
    ): List<ChannelEntity> = fetchLiveStreamsInternal(p, categoryRemoteId)

    private suspend fun fetchLiveStreamsInternal(
        p: ProviderEntity,
        categoryRemoteId: String?,
    ): List<ChannelEntity> {
        val extraQuery = categoryRemoteId
            ?.takeIf { it.isNotBlank() }
            ?.let { "&category_id=${it.urlEnc()}" }
            .orEmpty()

        return arrAt(p, "get_live_streams", extraQuery) { o ->
            val sid = o["stream_id"]?.str() ?: return@arrAt null
            val name = o["name"]?.str() ?: return@arrAt null
            val url = "${p.baseUrl}/live/${p.username.urlEnc()}/${p.password.urlEnc()}/$sid.ts"
            val tvArchive = o["tv_archive"]?.let { e -> e.str()?.toIntOrNull() ?: 0 } ?: 0
            val archiveDuration = o["tv_archive_duration"]?.let { e -> e.str()?.toIntOrNull() ?: 0 } ?: 0
            ChannelEntity(
                providerId = p.id,
                remoteId = sid,
                name = name,
                logo = o["stream_icon"]?.str(),
                categoryId = o["category_id"]?.str(),
                streamUrl = url,
                epgChannelId = o["epg_channel_id"]?.str()?.takeIf { it.isNotBlank() },
                catchupSource = null,
                catchupDays = if (tvArchive >= 1) archiveDuration.coerceAtLeast(1) else 0,
            )
        }
    }

    // ---- VOD (Movies) ----

    suspend fun fetchVodCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_vod_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "MOVIE", remoteId = rid, name = name)
    }

    suspend fun fetchVodStreams(p: ProviderEntity): List<MovieEntity> = arrAt(p, "get_vod_streams") { o ->
        val sid = o["stream_id"]?.str() ?: return@arrAt null
        val name = o["name"]?.str() ?: return@arrAt null
        val cont = o["container_extension"]?.str() ?: "mp4"
        val url = "${p.baseUrl}/movie/${p.username.urlEnc()}/${p.password.urlEnc()}/$sid.$cont"
        MovieEntity(
            providerId = p.id,
            remoteId = sid,
            name = name,
            poster = o["stream_icon"]?.str(),
            categoryId = o["category_id"]?.str(),
            streamUrl = url,
            container = cont,
            year = o["releaseDate"]?.str()?.take(4)?.toIntOrNull() ?: o["year"]?.str()?.toIntOrNull(),
            rating = o["rating"]?.str()?.toDoubleOrNull(),
            plot = null,
        )
    }

    // ---- Series ----

    suspend fun fetchSeriesCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_series_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "SERIES", remoteId = rid, name = name)
    }

    suspend fun fetchSeries(p: ProviderEntity): List<SeriesEntity> = arrAt(p, "get_series") { o ->
        val rid = o["series_id"]?.str() ?: return@arrAt null
        val name = o["name"]?.str() ?: return@arrAt null
        SeriesEntity(
            providerId = p.id,
            remoteId = rid,
            name = name,
            poster = o["cover"]?.str(),
            categoryId = o["category_id"]?.str(),
            year = o["releaseDate"]?.str()?.take(4)?.toIntOrNull(),
            rating = o["rating"]?.str()?.toDoubleOrNull(),
            plot = o["plot"]?.str(),
        )
    }

    /** Pull all episodes for one series. Returns pairs (season, episode_entity_without_id). */
    suspend fun fetchSeriesEpisodes(p: ProviderEntity, seriesRemoteId: String, seriesLocalId: Long): List<EpisodeEntity> {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=get_series_info&series_id=$seriesRemoteId")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val episodes = root["episodes"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<EpisodeEntity>()
        episodes.forEach { (seasonKey, listEl) ->
            val seasonNo = seasonKey.toIntOrNull() ?: 0
            val list = listEl as? JsonArray ?: return@forEach
            list.forEach { ep ->
                val o = ep as? JsonObject ?: return@forEach
                val rid = o["id"]?.str() ?: return@forEach
                val episodeNo = o["episode_num"]?.str()?.toIntOrNull() ?: 0
                val title = o["title"]?.str() ?: "Episode $episodeNo"
                val cont = o["container_extension"]?.str() ?: "mkv"
                val url = "${p.baseUrl}/series/${p.username.urlEnc()}/${p.password.urlEnc()}/$rid.$cont"
                out += EpisodeEntity(
                    seriesId = seriesLocalId,
                    remoteId = rid,
                    season = seasonNo,
                    episode = episodeNo,
                    title = title,
                    streamUrl = url,
                    container = cont,
                    plot = (o["info"] as? JsonObject)?.get("plot")?.str(),
                )
            }
        }
        return out
    }

    // ---- EPG ----

    /** Short EPG (next ~5 programmes) for a single channel. */
    suspend fun fetchShortEpg(p: ProviderEntity, channelRemoteId: String, channelLocalId: Long): List<EpgEntity> {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=get_short_epg&stream_id=$channelRemoteId")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val listings = root["epg_listings"] as? JsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = decodeBase64(o["title"]?.str()) ?: return@mapNotNull null
            val desc = decodeBase64(o["description"]?.str())
            val start = o["start_timestamp"]?.str()?.toLongOrNull()?.times(1000)
                ?: o["start"]?.str()?.let { parseDate(it) } ?: return@mapNotNull null
            val end = o["stop_timestamp"]?.str()?.toLongOrNull()?.times(1000)
                ?: o["end"]?.str()?.let { parseDate(it) } ?: (start + 30 * 60_000)
            EpgEntity(channelId = channelLocalId, title = title, description = desc, startMs = start, endMs = end)
        }
    }

    // ---- Helpers ----

    private suspend inline fun <T : Any> arrAt(
        p: ProviderEntity,
        action: String,
        extraQuery: String = "",
        crossinline transform: (JsonObject) -> T?,
    ): List<T> {
        val requestStarted = SystemClock.elapsedRealtime()
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=$action$extraQuery")
        val requestMs = SystemClock.elapsedRealtime() - requestStarted

        // Large provider catalogs can contain tens of thousands of entries.
        // Parsing/mapping them on a ViewModel's Main coroutine freezes Fire TV
        // navigation even though the HTTP request itself runs on IO.
        return withContext(Dispatchers.Default) {
            val parseStarted = SystemClock.elapsedRealtime()
            val parsed = json.parseToJsonElement(body)
            val arr = parsed as? JsonArray
            if (arr == null) {
                Log.w(
                    "FleezyCatalog",
                    "$action returned non-array payload; chars=${body.length}; requestMs=$requestMs",
                )
                return@withContext emptyList()
            }
            val mapped = arr.mapNotNull { (it as? JsonObject)?.let(transform) }
            val parseMs = SystemClock.elapsedRealtime() - parseStarted
            Log.i(
                "FleezyCatalog",
                "$action items=${mapped.size}; chars=${body.length}; requestMs=$requestMs; parseMs=$parseMs; scoped=${extraQuery.isNotEmpty()}",
            )
            mapped
        }
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val requestLabel = Regex("[?&]action=([^&]+)").find(url)?.groupValues?.getOrNull(1) ?: "account"
        repeat(3) { attempt ->
            val request = Request.Builder()
                .url(url)
                // Some Xtream/relay servers gate API access by player User-Agent.
                // Smarters is already confirmed against the IPTV Boss XC test endpoint,
                // so present the same widely-supported player identity for API calls.
                .header("User-Agent", "IPTVSmartersPro")
                .header("Accept", "*/*")
                .build()
            ok.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext resp.body?.string().orEmpty()
                }

                // Some Xtream servers can occasionally answer rapid API bursts with
                // HTTP 513 even though the same account/endpoints are healthy.
                // Retry only that transient status; fail immediately on all
                // normal authentication/network errors.
                if (resp.code != 513 || attempt == 2) {
                    error("Server returned HTTP ${resp.code} during $requestLabel")
                }
            }
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        error("Server request failed")
    }

    private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun decodeBase64(s: String?): String? = s?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    private fun parseDate(s: String): Long? = runCatching {
        // Xtream sends "yyyy-MM-dd HH:mm:ss" in UTC.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)?.time
    }.getOrNull()
}
