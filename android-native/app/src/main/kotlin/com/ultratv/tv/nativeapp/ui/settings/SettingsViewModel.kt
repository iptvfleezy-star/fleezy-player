package com.ultratv.tv.nativeapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.ProviderEntity
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val providers: List<ProviderEntity> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: ProviderRepository,
    private val prefs: com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore,
    private val backupRepo: com.ultratv.tv.nativeapp.data.repo.BackupRepository,
) : ViewModel() {

    val localLogosFolderUri: StateFlow<String> = prefs.flow
        .map { it.localLogosFolderUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setLocalLogosFolderUri(uri: String) =
        viewModelScope.launch { prefs.setLocalLogosFolderUri(uri) }

    private val _backupText = MutableStateFlow<String?>(null)
    val backupText: StateFlow<String?> = _backupText.asStateFlow()

    fun prepareBackup(
        readyMsg: String = "Backup ready — pick a file to save it.",
        password: String? = null,
    ) {
        viewModelScope.launch {
            _backupText.value = backupRepo.export(password)
            com.ultratv.tv.nativeapp.ui.common.Toaster.ok(readyMsg)
        }
    }

    fun consumeBackup(): String? {
        val t = _backupText.value
        _backupText.value = null
        return t
    }

    fun restoreBackup(
        text: String,
        restoredTemplate: String = "Restored %1\$d provider(s), %2\$d fav, %3\$d watch entries",
        failedPrefix: String = "Restore failed: ",
        password: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val r = backupRepo.import(text, password)
                com.ultratv.tv.nativeapp.ui.common.Toaster.ok(
                    restoredTemplate.format(r.providers, r.favorites, r.historyEntries)
                )
            } catch (t: Throwable) {
                com.ultratv.tv.nativeapp.ui.common.Toaster.err(failedPrefix + (t.message ?: ""))
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    private val _syncing = MutableStateFlow(false)

    val providers: StateFlow<List<ProviderEntity>> =
        repo.observeProviders().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val message: StateFlow<String?> = _message.asStateFlow()
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private suspend fun makeDefaultIfNone(newId: Long) {
        if (repo.firstActive() == null) repo.setDefault(newId)
    }

    fun setDefault(id: Long) {
        viewModelScope.launch {
            repo.setDefault(id)
            _message.value = "Default Fleezy account changed."
        }
    }

    fun addAndSync(name: String, baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Checking account…"
            try {
                val id = repo.addXtream(name, baseUrl, username, password)
                makeDefaultIfNone(id)
                val n = repo.syncAll(id) { _message.value = it }
                _message.value = "Ready — $n items"
            } catch (t: Throwable) {
                _message.value = "Sign in failed: " + (t.message ?: "Check your username and password")
            } finally {
                _syncing.value = false
            }
        }
    }

    fun resync(providerId: Long) {
        viewModelScope.launch {
            _syncing.value = true
            try {
                val n = repo.syncAll(providerId) { _message.value = it }
                _message.value = "Re-synced — $n items"
            } catch (t: Throwable) {
                _message.value = "Sync failed: " + (t.message ?: "Unknown error")
            } finally {
                _syncing.value = false
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
            _message.value = "Fleezy account removed"
        }
    }
}
