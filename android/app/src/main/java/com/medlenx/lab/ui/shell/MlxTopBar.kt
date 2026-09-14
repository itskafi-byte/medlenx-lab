package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

/**
 * Placeholder is kept at the web's full length on purpose. The Figma export shortens
 * it, but the anti-truncation contract wins: the real string in `templates/index.html`
 * is the one a user is meant to read.
 */
private const val SEARCH_PLACEHOLDER =
    "Search medicines, doctors, generics - always accessible from anywhere..."

/** `rgba(255,255,255,0.92)` + `blur(12px)` — the Figma AppBar's glass. */
private val AppBarHaze = HazeStyle(
    backgroundColor = Color.White.copy(alpha = 0.92f),
    blurRadius = 12.dp,
)

/** Live connection + sync state shown as chips in the app bar. */
data class TopBarState(
    val online: Boolean = true,
    val queuedScans: Int = 0,
    val companyName: String? = null,
    val latencyMs: Long? = null,
)

/**
 * Sticky app bar — Figma `AppBar` (App.tsx:302-330).
 *
 * 64dp tall, `rgba(255,255,255,0.92)`, 1dp #E2E8F0 bottom border, `padding:0 16px`,
 * `gap:8`. Holds the 32dp logo tile, the always-available global search, the status
 * chip cluster and the 32dp avatar. The `Install` PWA button is dropped — meaningless
 * in a native app.
 *
 * The CSS `backdrop-filter:"blur(12px)"` over `rgba(255,255,255,0.92)` is reproduced
 * with `dev.chrisbanes.haze`: this bar is the haze *child* and the shell's scrolling
 * content is the haze *source*. Haze uses `RenderEffect` on API 31+ and degrades to
 * the plain scrim below that, which is the same thing the bar already drew.
 */
@Composable
fun MlxTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    state: TopBarState,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Figma is `height:64` *including* the 1px bottom border (Tailwind sets
            // border-box globally). MedLenXShell draws that border as a separate 1dp
            // Divider, so the bar itself is 63dp and the pair totals 64.
            .height(MlxD.AppBarHeight - 1.dp)
            .hazeChild(state = hazeState, style = AppBarHaze)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.clickable(onClick = onSearchClick)) {
            BrandTile(size = 32.dp)
        }

        SearchField(query = query, onQueryChange = onQueryChange, modifier = Modifier.weight(1f))

        StatusChipRow(state = state)

        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Mlx.Brand900, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = "Profile",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Global search — Figma `AppBar` input: `height:36`, `borderRadius:12`,
 * `border:1px solid #E2E8F0` turning `#2563EB` on focus with an outer
 * `box-shadow:0 0 0 2px rgba(37,99,235,0.20)` ring, `padding:"0 12px 0 32px"`
 * (32px leading clears the magnifier, which sits at `left:10`), `fontSize:11`.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(MlxD.SearchFieldHeight)
            // Outer focus ring first, then the solid hairline over its inner edge.
            .border(
                width = 2.dp,
                color = if (focused) Mlx.Brand500.copy(alpha = 0.20f) else Color.Transparent,
                shape = MlxShape.Medium,
            )
            .background(Mlx.Surface, MlxShape.Medium)
            .border(
                width = 1.dp,
                color = if (focused) Mlx.Brand500 else Mlx.Brand200,
                shape = MlxShape.Medium,
            ),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = Mlx.Text400,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(12.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MlxType.BodySmall.copy(color = Mlx.Text900, fontSize = 11.sp),
            cursorBrush = SolidColor(Mlx.Brand500),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = SEARCH_PLACEHOLDER,
                            style = MlxType.BodySmall.copy(fontSize = 11.sp),
                            color = Mlx.Text400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 12.dp)
                .onFocusChanged { focused = it.isFocused },
        )
    }
}

/**
 * Status chips — Figma `AppBar` shows Online · queued · company; latency is an
 * Android-only addition (the web header has no such chip) and is kept because it is
 * the only place the user sees OpenRouter round-trip cost.
 *
 * The Online pill carries a 6dp #047857 dot, matching the Figma markup.
 */
@Composable
private fun StatusChipRow(state: TopBarState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(
            text = if (state.online) "Online" else "Offline",
            tone = if (state.online) PillTone.Emerald else PillTone.Red,
            dotColor = if (state.online) Mlx.Ok600 else null,
        )
        if (state.queuedScans > 0) {
            StatusPill(
                text = "${state.queuedScans} queued",
                tone = PillTone.Dark,
                icon = Icons.Filled.CloudUpload,
            )
        }
        // Full web string (templates/index.html:176), not Figma's shortened "Set company".
        StatusPill(
            text = state.companyName ?: "Set company in Settings",
            tone = PillTone.Dark,
            icon = Icons.Filled.Business,
        )
        if (state.latencyMs != null) {
            StatusPill(
                text = "${state.latencyMs} ms",
                tone = PillTone.Emerald,
                icon = Icons.Filled.SignalCellularAlt,
            )
        }
    }
}
