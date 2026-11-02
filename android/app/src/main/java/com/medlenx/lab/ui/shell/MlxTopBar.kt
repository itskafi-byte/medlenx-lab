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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.components.OfficerAvatar
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType
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
    onSettingsClick: () -> Unit,
    state: TopBarState,
    avatarUri: String?,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().hazeEffect(state = hazeState, style = AppBarHaze)) {
        val compact = maxWidth < 390.dp || LocalDensity.current.fontScale > 1.3f
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandHeader(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    HeaderActions(state, avatarUri, onSearchClick, onSettingsClick)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BrandHeader(Modifier.weight(1f))
                HeaderActions(state, avatarUri, onSearchClick, onSettingsClick)
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
private fun HeaderActions(state: TopBarState, avatarUri: String?, onSearchClick: () -> Unit, onSettingsClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Mlx.Text900)
        }
        CloudStatus(state)
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Mlx.Text900)
        }
        OfficerAvatar(imageUri = avatarUri, size = 32.dp, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun CloudStatus(state: TopBarState) {
    val count = state.pendingUploads
    val status = when {
        count == null -> if (state.online) "Online, checking upload queue" else "Offline, checking upload queue"
        state.online -> "Online, $count scans pending upload"
        else -> "Offline, $count scans pending upload"
    }
    val tint = when {
        !state.online -> Mlx.Text500
        count == null -> Mlx.Text400
        count > 0 -> Mlx.Warn600
        else -> Mlx.Ok500
    }
    Box(Modifier.size(44.dp).semantics(mergeDescendants = true) { contentDescription = status },
        contentAlignment = Alignment.Center) {
        val cloudIcon = when {
            !state.online -> Icons.Filled.CloudOff
            count == null -> Icons.Filled.Cloud
            count > 0 -> Icons.Outlined.CloudUpload
            else -> Icons.Filled.Cloud
        }
        Icon(cloudIcon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (count != null && count > 0) {
            Box(Modifier.align(Alignment.TopEnd).widthIn(min = 18.dp).heightIn(min = 18.dp)
                .background(Mlx.Warn600, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(if (count > 99) "99+" else count.toString(), style = MlxType.MicroPill,
                    color = Color.White, maxLines = 1)
            }
        }
    }
}
