package com.ultratv.tv.nativeapp.ui.parental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.ChannelDao
import com.ultratv.tv.nativeapp.data.db.ChannelEntity
import com.ultratv.tv.nativeapp.data.prefs.LockedChannelsStore
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.ui.settings.AddProviderDialog
import com.ultratv.tv.nativeapp.ui.settings.FormField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LockedChannelsViewModel @Inject constructor(
    private val providerRepo: ProviderRepository,
    private val channelDao: ChannelDao,
    private val store: LockedChannelsStore,
) : ViewModel() {

    private val providers = providerRepo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeProviderId: StateFlow<Long?> = providers
        .map { ps -> (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val locked: StateFlow<Set<String>> = store.locked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val totalChannels: StateFlow<Int> = activeProviderId
        .mapLatest { pid -> if (pid == null) 0 else channelDao.count(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lockedCount: StateFlow<Int> = combine(activeProviderId, locked) { pid, entries ->
        if (pid == null) 0
        else {
            val prefix = pid.toString() + ":"
            entries.count { it.startsWith(prefix) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Never materialise the entire Live catalogue here. With no search term we
     * resolve only already-locked channels; with a useful term Room returns at
     * most the bounded search result set.
     */
    val channels: StateFlow<List<ChannelEntity>> = combine(
        activeProviderId,
        _query,
        locked,
    ) { pid, query, entries -> Triple(pid, query.trim(), entries) }
        .mapLatest { (pid, query, entries) ->
            if (pid == null) return@mapLatest emptyList()

            if (query.length >= 2) {
                channelDao.search(pid, query)
            } else {
                val prefix = pid.toString() + ":"
                val remoteIds = entries.asSequence()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it.isNotBlank() }
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value.trim().take(80)
    }

    fun clearSearch() {
        _query.value = ""
    }

    fun toggle(channel: ChannelEntity, on: Boolean) {
        viewModelScope.launch { store.set(channel.providerId, channel.remoteId, on) }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun LockedChannelsScreen(vm: LockedChannelsViewModel = hiltViewModel()) {
    val chans by vm.channels.collectAsState()
    val locked by vm.locked.collectAsState()
    val query by vm.query.collectAsState()
    val totalChannels by vm.totalChannels.collectAsState()
    val lockedCount by vm.lockedCount.collectAsState()
    var searchDialog by remember { mutableStateOf(false) }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            S.lockChannelsTitle,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            S.lockChannelsSubtitle.format(totalChannels, lockedCount),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { searchDialog = true }) {
                Text(
                    if (query.isBlank()) "Search channels"
                    else "Search: \"$query\"",
                )
            }
            if (query.isNotBlank()) {
                Button(
                    onClick = vm::clearSearch,
                    colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Text("Show locked")
                }
            }
            Text(
                if (query.isBlank()) {
                    "Showing individually locked channels. Search to lock another channel."
                } else {
                    "Showing up to 50 matching channels."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (chans.isEmpty()) {
            Text(
                if (query.isBlank()) {
                    "No individually locked channels yet. Search for a channel to lock."
                } else {
                    "No matching channels."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(chans, key = { it.id }) { channel ->
                    val isLocked = storeKey(channel.providerId, channel.remoteId) in locked
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            channel.name + if (isLocked) "  🔒" else "",
                            fontSize = 15.sp,
                            color = if (isLocked) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { vm.toggle(channel, !isLocked) },
                            colors = if (isLocked) {
                                ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                            } else {
                                ButtonDefaults.colors()
                            },
                        ) {
                            Text(if (isLocked) S.lockChannelsUnlock else S.lockChannelsLock)
                        }
                    }
                }
            }
        }
    }

    if (searchDialog) {
        var draft by remember(query) { mutableStateOf(query) }
        AddProviderDialog(
            title = "Find a channel",
            onDismiss = { searchDialog = false },
            onSubmit = {
                vm.setQuery(draft)
                searchDialog = false
            },
            canSubmit = draft.trim().length >= 2,
            submitLabel = "Search",
        ) {
            Text(
                "Enter at least 2 characters. Fleezy searches the active Live TV lineup without loading the whole catalogue.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            FormField(
                label = "Channel name",
                value = draft,
                onChange = { draft = it.take(80) },
                placeholder = "e.g. TSN",
            )
        }
    }
}

private fun storeKey(providerId: Long, remoteId: String): String =
    providerId.toString() + ":" + remoteId
