package com.ultratv.tv.nativeapp.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ultratv.tv.nativeapp.data.db.CategoryEntity

@Composable
fun CategoryChips(
    categories: List<CategoryEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val listState = rememberLazyListState()

    // Large IPTV libraries can expose dozens or hundreds of categories. Keep
    // the selected provider-ordered category visible without composing every
    // chip up front or stealing D-pad focus.
    LaunchedEffect(selected, categories) {
        val target = if (selected == null) {
            0
        } else {
            categories.indexOfFirst { it.remoteId == selected }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: 0
        }
        listState.scrollToItem(target)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("category-all") {
            Chip(text = "All", on = selected == null) { onSelect(null) }
        }
        items(
            items = categories,
            key = { it.id },
        ) { cat ->
            Chip(
                text = cat.name + if (cat.locked) " 🔒" else "",
                on = selected == cat.remoteId,
            ) { onSelect(cat.remoteId) }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(20.dp)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = if (on) ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) else ButtonDefaults.colors(),
    ) { Text(text, fontSize = 14.sp) }
}
