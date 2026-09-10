package com.ultratv.tv.nativeapp.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.i18n.LocalStrings
import com.ultratv.tv.nativeapp.ui.common.ChannelLogo
import com.ultratv.tv.nativeapp.ui.theme.UltraFonts
import com.ultratv.tv.nativeapp.ui.theme.UltraTokens
import com.ultratv.tv.nativeapp.ui.theme.ultraCardColors

/**
 * OTT-style channel-zap drawer used by the full-screen player. Slides in from
 * the right while the live stream keeps playing in the background. Each row
 * carries the channel position, logo, name and now/next programmes; the
 * currently-playing channel is highlighted with an EN COURS pill. Picking a
 * row zaps without leaving the player.
 *
 * Extracted from PlayerScreen for readability; visibility is `internal` so
 * PlayerScreen can still reach it without going public.
 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveDrawer(
    vm: PlayerViewModel,
    onPick: (com.ultratv.tv.nativeapp.data.db.ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val drawerState by vm.queue.collectAsState()
    val channels = drawerState?.channels.orEmpty()
    val currentIndex = drawerState?.index ?: -1
    val s = LocalStrings.current
    val t = UltraTokens
    val f = UltraFonts
    val listState = rememberLazyListState()
    val currentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { currentFocusRequester.requestFocus() }
        }
    }

    BackHandler { onDismiss() }
    Row(Modifier.fillMaxSize()) {
        // Click-through area on the left so OK / BACK reach us first.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0x66000000))
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(460.dp)
                .background(Color(0xEE0B0B12))
                .border(1.dp, t.Line2, RoundedCornerShape(0.dp))
                .padding(18.dp),
        ) {
            Text(
                s.liveZappingEyebrow,
                color = t.Fg3,
                fontSize = 11.sp,
                letterSpacing = 2.3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                s.liveAllChannels,
                color = t.Fg,
                fontFamily = f.Serif,
                fontSize = 24.sp,
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    items = channels,
                    key = { _, channel -> channel.id },
                ) { index, channel ->
                    val isCurrent = index == currentIndex
                    val programmes = drawerState?.nowNext?.get(channel.id)
                    val nowProgramme = programmes?.first
                    val nextProgramme = programmes?.second
                    Card(
                        onClick = { onPick(channel) },
                        modifier = if (isCurrent) {
                            Modifier.focusRequester(currentFocusRequester)
                        } else {
                            Modifier
                        },
                        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ultraCardColors(
                            containerColor = if (isCurrent) t.AccentSoft else Color.Transparent,
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%02d".format(index + 1),
                                color = if (isCurrent) t.Accent else t.Fg4,
                                fontSize = 12.sp,
                                fontFamily = f.Mono,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(32.dp),
                            )
                            ChannelLogo(
                                name = channel.name,
                                logoUrl = channel.logo,
                                short = null,
                                hueSeed = channel.name.hashCode(),
                                hd = null,
                                size = 36.dp,
                                showBadge = false,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        channel.name,
                                        color = if (isCurrent) t.Fg else t.Fg2,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                    if (isCurrent) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(t.Accent)
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                s.liveOnAirPill,
                                                color = Color.White,
                                                fontSize = 8.sp,
                                                letterSpacing = 0.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                                if (nowProgramme != null) {
                                    Text(
                                        nowProgramme.title,
                                        color = t.Fg3,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                }
                                if (nextProgramme != null) {
                                    Text(
                                        "${s.liveThen} ${nextProgramme.title}",
                                        color = t.Fg4,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
