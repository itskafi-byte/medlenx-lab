package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

private val AppBarHaze = HazeStyle(
    backgroundColor = Color.White.copy(alpha = 0.92f),
    blurRadius = 12.dp,
    tint = null,
)

/** The outbox count is UID-scoped and separate from the image-processing queue. */
data class TopBarState(val online: Boolean = true, val pendingUploads: Int? = null)

@Composable
fun MlxTopBar(
    onSearchClick: () -> Unit,
    state: TopBarState,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().hazeEffect(state = hazeState, style = AppBarHaze),
    ) {
        // Keeping two rows on narrow screens or large text avoids overlapping or
        // truncating the controls while retaining the same order and touch targets.
        val compact = maxWidth < 460.dp || LocalDensity.current.fontScale > 1.15f
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandHeader(Modifier.weight(1f))
                    SearchButton(onSearchClick)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    StatusGroup(state)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrandHeader(Modifier.weight(1f))
                SearchButton(onSearchClick)
                StatusGroup(state)
            }
        }
    }
}

@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrandTile(size = 32.dp)
        Text("MedLenX Lab", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = Mlx.Text900, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Mlx.Text900)
    }
}

@Composable
private fun StatusGroup(state: TopBarState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(
            text = if (state.online) "Online" else "Offline",
            tone = if (state.online) PillTone.Emerald else PillTone.Red,
            dotColor = if (state.online) Mlx.Ok600 else null,
            maxLines = 1,
        )
        val count = state.pendingUploads
        StatusPill(
            text = if (count == null) "— Queued" else "$count Queued",
            tone = if ((count ?: 0) > 0) PillTone.Amber else PillTone.Slate,
            icon = Icons.Filled.CloudUpload,
            maxLines = 1,
        )
        Box(Modifier.size(32.dp).background(Mlx.Brand900, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, contentDescription = "Profile", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
