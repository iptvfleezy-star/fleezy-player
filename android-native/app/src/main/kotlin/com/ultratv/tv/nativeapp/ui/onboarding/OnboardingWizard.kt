package com.ultratv.tv.nativeapp.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.FleezyConfig
import com.ultratv.tv.nativeapp.data.prefs.UserPreferencesStore
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import com.ultratv.tv.nativeapp.ui.settings.FormField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: UserPreferencesStore,
    private val provider: ProviderRepository,
) : ViewModel() {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val show: StateFlow<Boolean> = combine(
        provider.observeProviders(),
        _syncing,
    ) { providers, syncing ->
        providers.isEmpty() || syncing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isBlank() || _syncing.value) return

        viewModelScope.launch {
            _syncing.value = true
            _message.value = "Signing in…"
            var providerId: Long? = null
            try {
                val id = provider.addXtream(
                    FleezyConfig.PROVIDER_NAME,
                    FleezyConfig.XTREAM_BASE_URL,
                    username.trim(),
                    password,
                )
                providerId = id
                provider.setDefault(id)

                // First launch is Live-first: do not make a Fire Stick wait for
                // a huge VOD/Series catalog before the customer can watch TV.
                val liveCount = provider.syncXtreamLiveOnly(id) { step -> _message.value = step }
                prefs.markOnboardingSeen()
                _message.value = "Ready — $liveCount live channels"
                _syncing.value = false

                // Finish Movies + Series after the onboarding overlay disappears.
                // Failure here leaves the validated account + Live catalog intact.
                viewModelScope.launch {
                    runCatching {
                        provider.syncXtreamLibraryOnly(id)
                    }
                }
            } catch (t: Throwable) {
                providerId?.let { id ->
                    runCatching { provider.delete(id) }
                }
                _message.value = "Sign in failed: " + (t.message ?: "Check your username and password")
            } finally {
                _syncing.value = false
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingWizard(
    onOpenSettings: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val show by vm.show.collectAsState()
    if (!show) return

    val syncing by vm.syncing.collectAsState()
    val message by vm.message.collectAsState()
    var username = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var password = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 680.dp)
                .background(Color(0xFF111318), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF2B2F38), RoundedCornerShape(24.dp))
                .padding(horizontal = 42.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "FLEEZY",
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 5.sp,
            )
            Text(
                text = "PLAYER",
                color = Color(0xFF9EA4AF),
                fontSize = 13.sp,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Sign in",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Enter the username and password provided with your Fleezy account.",
                color = Color(0xFFA8ADB7),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            FormField(
                label = "Username",
                value = username.value,
                onChange = { username.value = it },
                autoFocus = true,
            )
            FormField(
                label = "Password",
                value = password.value,
                onChange = { password.value = it },
                password = true,
            )

            Button(
                onClick = { vm.signIn(username.value, password.value) },
                enabled = !syncing && username.value.isNotBlank() && password.value.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (syncing) "LOADING…" else "SIGN IN", fontWeight = FontWeight.Bold)
            }

            message?.let {
                Text(
                    text = it,
                    color = if (it.startsWith("Sign in failed")) Color(0xFFFF8A80) else Color(0xFFA8ADB7),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
