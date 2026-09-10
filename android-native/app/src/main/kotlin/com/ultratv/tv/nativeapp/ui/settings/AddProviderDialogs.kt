package com.ultratv.tv.nativeapp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Full-screen modal scrim hosting a form. Lets us keep the Settings list above
 * 100% text/buttons/switches so D-pad scroll never lands on a TextField that
 * would summon the IME mid-scroll. Inputs only ever appear when the user has
 * explicitly opened one of these dialogs.
 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun AddProviderDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    canSubmit: Boolean,
    submitLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val cancelFocusRequester = remember { FocusRequester() }

    val dismissDialog = {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onDismiss()
    }
    val submitDialog = {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onSubmit()
    }

    // Fire TV's system keyboard can otherwise retain a text-field focus and
    // make the sign-in overlay feel impossible to leave. Always give Back an
    // explicit escape route, and start the dialog on a normal TV button rather
    // than automatically opening the IME.
    BackHandler(onBack = dismissDialog)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { cancelFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = submitDialog,
                    enabled = canSubmit,
                ) { Text(submitLabel ?: S.addProviderAdd, fontSize = 15.sp) }
                Button(
                    onClick = dismissDialog,
                    modifier = Modifier.focusRequester(cancelFocusRequester),
                    colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) { Text(S.cancel, fontSize = 15.sp) }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    focusRequester: FocusRequester? = null,
    onDpadUp: (() -> Unit)? = null,
    onDpadDown: (() -> Unit)? = null,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background)
                .androidx_border(focused)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                ),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (onDpadUp != null) {
                                    onDpadUp()
                                    true
                                } else false
                            }
                            Key.DirectionDown -> {
                                if (onDpadDown != null) {
                                    onDpadDown()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                    inner()
                },
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamDialog(onDismiss: () -> Unit, onSubmit: (name: String, url: String, user: String, pass: String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val canSubmit = user.isNotBlank() && pass.isNotBlank()
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current

    AddProviderDialog(
        title = "Sign in to Fleezy",
        onDismiss = onDismiss,
        onSubmit = {
            onSubmit(
                com.ultratv.tv.nativeapp.FleezyConfig.PROVIDER_NAME,
                com.ultratv.tv.nativeapp.FleezyConfig.XTREAM_BASE_URL,
                user.trim(),
                pass,
            )
        },
        canSubmit = canSubmit,
        submitLabel = "Sign in",
    ) {
        FormField(S.fieldUsername, user, { user = it })
        FormField(S.fieldPassword, pass, { pass = it }, password = true)
    }
}

@Composable
private fun Modifier.androidx_border(focused: Boolean): Modifier = this.border(
    width = if (focused) 2.dp else 1.dp,
    color = if (focused) com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Accent else com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Line2,
    shape = RoundedCornerShape(8.dp),
)
