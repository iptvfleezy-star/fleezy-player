package com.ultratv.tv.nativeapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.ui.common.ContentRail
import com.ultratv.tv.nativeapp.ui.common.PosterCard
import com.ultratv.tv.nativeapp.ui.theme.UltraFonts
import com.ultratv.tv.nativeapp.ui.theme.UltraTokens

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onGoLive: () -> Unit,
    onGoMovies: () -> Unit,
    onGoSeries: () -> Unit,
    onGoGuide: () -> Unit = {},
    onGoFavorites: () -> Unit = {},
    onPlay: (url: String, title: String) -> Unit = { _, _ -> },
    onOpenMovie: (Long) -> Unit = {},
    onOpenSeries: (Long) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val providers by vm.providers.collectAsState()
    val continueW by vm.continueWatching.collectAsState()
    val recent by vm.recentlyWatched.collectAsState()
    val movies by vm.featuredMovies.collectAsState()
    val series by vm.featuredSeries.collectAsState()
    val channels by vm.featuredChannels.collectAsState()

    var actionsFor by remember { mutableStateOf<com.ultratv.tv.nativeapp.data.db.WatchHistoryEntity?>(null) }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    val activeProvider = providers.firstOrNull { it.active } ?: providers.firstOrNull()
    val recentLive = recent
        .asSequence()
        .filter { it.kind == "LIVE" }
        .distinctBy { it.remoteId }
        .take(12)
        .toList()
    val continueKeys = continueW.map { it.kind to it.remoteId }.toSet()
    val recentNonLive = recent.filter {
        it.kind != "LIVE" && (it.kind to it.remoteId) !in continueKeys
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Compact TV-first header. Keep the primary actions above the fold
        // instead of making customers scroll past an auto-selected VOD hero.
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = UltraTokens.EdgeGutter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "FLEEZY",
                    color = UltraTokens.Fg,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.2.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Live TV, movies and series",
                    color = UltraTokens.Fg3,
                    fontSize = 14.sp,
                )
            }
            if (activeProvider != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = UltraTokens.Accent, fontSize = 12.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Connected",
                        color = UltraTokens.Fg2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Primary TV destinations — keep the most-used actions visible without
        // making customers hunt through the sidebar.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = UltraTokens.EdgeGutter),
        ) {
            Text(
                "WATCH",
                color = UltraTokens.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeQuickAction("LIVE TV", "Watch channels", primary = true, onClick = onGoLive)
                HomeQuickAction("TV GUIDE", "What's on now", onClick = onGoGuide)
                HomeQuickAction("FAVORITES", "Your channels", onClick = onGoFavorites)
                HomeQuickAction("MOVIES", "Browse VOD", onClick = onGoMovies)
                HomeQuickAction("SERIES", "Browse shows", onClick = onGoSeries)
            }
        }

        Spacer(Modifier.height(24.dp))

        if (recentLive.isNotEmpty()) {
            ContentRail(
                title = "Recently watched channels",
                eyebrow = "Live TV",
                cardWidth = 260.dp,
                items = recentLive,
                itemKey = { "live-${it.providerId}-${it.remoteId}" },
            ) { h ->
                PosterCard(
                    title = h.title,
                    poster = h.poster,
                    subtitle = "Live",
                    aspect = 16f / 9f,
                ) {
                    vm.playFromHistory(h, onPlay)
                }
            }
        }

        if (channels.isNotEmpty()) {
            ContentRail(
                title = S.homeFeaturedChannels,
                eyebrow = "Live TV",
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
                    vm.playChannel(c, onPlay)
                }
            }
        }

        // ---- Continue watching ----
        if (continueW.isNotEmpty()) {
            ContentRail(
                title = S.homeContinueWatching,
                eyebrow = "For you",
                cardWidth = 300.dp,
                items = continueW,
                itemKey = { "h-${it.kind}-${it.remoteId}" },
            ) { h ->
                val progress = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs.toFloat()) else 0f
                val remainingMs = (h.durationMs - h.positionMs).coerceAtLeast(0)
                val mins = (remainingMs / 60_000).toInt()
                val remaining = if (h.durationMs <= 0) null
                    else if (mins >= 60) "${mins / 60}h ${mins % 60}"
                    else "$mins min"
                com.ultratv.tv.nativeapp.ui.common.ContinueWatchingTile(
                    title = h.title,
                    poster = h.poster,
                    epLabel = h.kind.lowercase().replaceFirstChar { it.uppercase() },
                    remaining = remaining,
                    progress = progress,
                    hueSeed = h.title.hashCode(),
                    onClick = { actionsFor = h },
                )
            }
        }

        if (recentNonLive.isNotEmpty()) {
            ContentRail(
                title = S.homeRecentlyWatched,
                cardWidth = 240.dp,
                items = recentNonLive,
                itemKey = { "r-${it.kind}-${it.remoteId}" },
            ) { h ->
                PosterCard(
                    title = h.title,
                    poster = h.poster,
                    subtitle = h.kind.lowercase().replaceFirstChar { it.uppercase() },
                    aspect = 16f / 9f,
                ) {
                    vm.playFromHistory(h, onPlay)
                }
            }
        }

        if (movies.isNotEmpty()) {
            ContentRail(
                title = S.homeFeaturedMovies,
                eyebrow = "Movies",
                items = movies,
                itemKey = { it.id },
            ) { m ->
                PosterCard(
                    title = m.name,
                    poster = m.poster,
                    subtitle = m.year?.toString(),
                ) { onOpenMovie(m.id) }
            }
        }

        if (series.isNotEmpty()) {
            ContentRail(
                title = S.seriesTitle,
                eyebrow = "Series",
                items = series,
                itemKey = { it.id },
            ) { s ->
                PosterCard(
                    title = s.name,
                    poster = s.poster,
                    subtitle = s.year?.toString(),
                ) { onOpenSeries(s.id) }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    // Action sheet for a Continue watching entry.
    actionsFor?.let { h ->
        ContinueActions(
            title = h.title,
            onResume = {
                vm.playFromHistory(h, onPlay)
                actionsFor = null
            },
            onDismiss = {
                vm.dismiss(h)
                actionsFor = null
            },
            onCancel = { actionsFor = null },
        )
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeQuickAction(
    title: String,
    subtitle: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(200.dp)
            .height(76.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) UltraTokens.CtaBg else UltraTokens.Surface2,
            contentColor = if (primary) UltraTokens.CtaFgOnCta else UltraTokens.Fg,
            focusedContainerColor = if (primary) UltraTokens.CtaBg else UltraTokens.SurfaceStrong,
            focusedContentColor = if (primary) UltraTokens.CtaFgOnCta else UltraTokens.Fg,
        ),
    ) {
        Column {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (primary) UltraTokens.CtaFgOnCta else UltraTokens.Fg,
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = if (primary) UltraTokens.CtaFgOnCta.copy(alpha = 0.72f) else UltraTokens.Fg3,
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueActions(
    title: String,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, UltraTokens.Line2, RoundedCornerShape(20.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                fontFamily = UltraFonts.Serif,
                fontSize = 26.sp,
                color = UltraTokens.Fg,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.colors(
                        containerColor = UltraTokens.CtaBg,
                        contentColor = UltraTokens.CtaFgOnCta,
                    ),
                ) { Text("▶  " + S.resume, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(containerColor = UltraTokens.Surface2),
                ) { Text("✖  " + S.dismiss, color = UltraTokens.Fg2) }
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.colors(containerColor = Color.Transparent),
                ) { Text(S.cancel, color = UltraTokens.Fg3) }
            }
        }
    }
}
