package com.ultratv.tv.nativeapp.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class OpenDialog { NONE, XTREAM }

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val providers by vm.providers.collectAsState()
    val message by vm.message.collectAsState()
    val syncing by vm.syncing.collectAsState()

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var openDialog by remember { mutableStateOf(OpenDialog.NONE) }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    val savedMsg = S.toastBackupSaved
    val saveFailedMsg = S.toastSaveFailed
    val emptyFileMsg = S.toastEmptyFile
    val backupReadyMsg = S.toastBackupReady
    val restoredTemplate = S.toastRestoredTemplate
    val restoreFailedPrefix = S.toastRestoreFailed

    // SAF picker for local M3U files. Kept here at the top so the contract is
    // remembered across recompositions; the trigger is a Button further down.
    // Backup export: SAF CreateDocument with a JSON mime hint. The VM has
    // already serialised the bundle to text via prepareBackup() before we
    // get here, so we just stream it to the picked URI.
    // Backup encryption password — shared between export and restore so the
    // user can also use it as the decryption hint when re-importing.
    var backupPwd by remember { mutableStateOf("") }

    val saveBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            val text = vm.consumeBackup()
            if (uri == null || text == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.onSuccess {
                    com.ultratv.tv.nativeapp.ui.common.Toaster.ok(savedMsg)
                }.onFailure {
                    com.ultratv.tv.nativeapp.ui.common.Toaster.err(saveFailedMsg + (it.message ?: ""))
                }
            }
        },
    )
    val loadBackup = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val txt = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8).orEmpty()
                }.getOrNull()
                if (txt.isNullOrBlank()) {
                    com.ultratv.tv.nativeapp.ui.common.Toaster.err(emptyFileMsg)
                } else {
                    vm.restoreBackup(
                        text = txt,
                        restoredTemplate = restoredTemplate,
                        failedPrefix = restoreFailedPrefix,
                        password = backupPwd.takeIf { it.isNotEmpty() },
                    )
                }
            }
        },
    )

    // SAF tree picker for local channel logos. Takes a persistable read perm
    // so subsequent app launches can still read from the folder.
    val pickLogosFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { tree ->
            if (tree == null) return@rememberLauncherForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(tree, flags) }
            vm.setLocalLogosFolderUri(tree.toString())
            com.ultratv.tv.nativeapp.ui.common.Toaster.ok("Logo folder saved")
        },
    )

    val T = com.ultratv.tv.nativeapp.ui.theme.UltraTokens
    val F = com.ultratv.tv.nativeapp.ui.theme.UltraFonts
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = T.EdgeGutter, end = T.EdgeGutter, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "SETTINGS",
            color = T.Fg3,
            fontSize = 11.sp,
            letterSpacing = 2.3.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            S.settingsTitle,
            fontFamily = F.Serif,
            fontSize = 56.sp,
            lineHeight = 56.sp,
            letterSpacing = (-1.5).sp,
            color = T.Fg,
        )
        Spacer(Modifier.height(8.dp))

        // ---- 2. Fleezy account ----
        SectionCard {
            Text("Fleezy account", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Sign in with the username and password provided for your Fleezy account.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openDialog = OpenDialog.XTREAM }) { Text("Sign in to Fleezy") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        }

        // ---- 3. Configured providers ----
        SectionCard {
            Text("${S.settingsConfiguredHeader} (${providers.size})", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (providers.isEmpty()) {
                Text(S.settingsNoneYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                providers.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (p.active) {
                                    Text(
                                        S.settingsDefaultBadge,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Text(p.name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        if (!p.active) {
                            Button(onClick = { vm.setDefault(p.id) }, enabled = !syncing) { Text(S.settingsSetDefault) }
                        }
                        Button(onClick = { vm.resync(p.id) }, enabled = !syncing) { Text(S.settingsResync) }
                        Button(
                            onClick = { vm.delete(p.id) },
                            enabled = !syncing,
                            colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) { Text(S.delete) }
                    }
                }
            }
        }

        // ---- 4. Display & playback ----
        SectionCard {
            Text(S.settingsDisplay, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            PreferencesSection()
        }

        // ---- 4b. Backup / restore ----
        SectionCard {
            Text(S.settingsBackupTitle, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                S.settingsBackupHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
            Text(
                S.backupEncryptHint,
                color = T.Fg3,
                fontSize = 12.sp,
            )
            com.ultratv.tv.nativeapp.ui.settings.FormField(
                label = S.backupEncryptFieldLabel,
                value = backupPwd,
                onChange = { backupPwd = it },
                password = true,
                placeholder = S.backupEncryptFieldPlaceholder,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.prepareBackup(backupReadyMsg, password = backupPwd.takeIf { it.isNotEmpty() })
                    val suffix = if (backupPwd.isNotEmpty()) "encrypted" else "plain"
                    saveBackup.launch("fleezy-player-backup-${System.currentTimeMillis()}-$suffix.json")
                }) { Text(S.settingsBackupExport) }
                Button(onClick = {
                    loadBackup.launch(arrayOf("application/json", "*/*"))
                }) { Text(S.settingsBackupImport) }
            }
        }

        // ---- 4c. Local channel logos ----
        SectionCard {
            Text("Local channel logos", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Choose a folder (USB or internal storage) containing PNG files named after the tvg-id or channel name. " +
                    "Files in this folder override provider logos in the app.",
                color = T.Fg3,
                fontSize = 12.sp,
            )
            val currentUri by vm.localLogosFolderUri.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { pickLogosFolder.launch(null) }) {
                    Text(if (currentUri.isBlank()) "Choose folder" else "Change folder", fontSize = 14.sp)
                }
                if (currentUri.isNotBlank()) {
                    Text(
                        text = currentUri.takeLast(60).let { if (currentUri.length > 60) "…$it" else it },
                        color = T.Fg3,
                        fontSize = 11.sp,
                        fontFamily = com.ultratv.tv.nativeapp.ui.theme.UltraFonts.Mono,
                    )
                }
            }
        }

        // ---- 5. Parental ----
        SectionCard {
            Text(S.settingsParental, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            com.ultratv.tv.nativeapp.ui.parental.ParentalSection(
                onManageLockedChannels = { onNavigate("locked-channels") },
            )
            Text(
                S.settingsParentalHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    // ---- Dialogs ----
    when (openDialog) {
        OpenDialog.XTREAM -> XtreamDialog(
            onDismiss = { openDialog = OpenDialog.NONE },
            onSubmit = { name, url, user, pass ->
                vm.addAndSync(name, url, user, pass); openDialog = OpenDialog.NONE
            },
        )
        OpenDialog.NONE -> Unit
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Surface1)
            .androidx_border()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Modifier.androidx_border(): Modifier =
    this.border(
        1.dp,
        com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Line,
        RoundedCornerShape(16.dp),
    )

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope
