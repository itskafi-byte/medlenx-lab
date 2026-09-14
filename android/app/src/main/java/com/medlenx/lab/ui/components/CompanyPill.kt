package com.medlenx.lab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/** How well the dispensing company was established during verification. */
enum class CompanyVerification { Verified, Unverified, None }

/**
 * Monogram tile — Figma `CompanyBadge` (App.tsx:78-84).
 *
 * Initials are the first letter of the first two words, uppercased. Square, radius 6,
 * `#0284C7`, white, glyph at 45% of the tile size, weight 700.
 */
@Composable
fun CompanyBadge(name: String, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val initials = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
    Box(
        modifier = modifier
            .size(size)
            .background(Mlx.Cyan700, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MlxType.MicroPill.copy(
                fontSize = (size.value * 0.45f).sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
        )
    }
}

/**
 * Company pill — Figma `CompanyPill` (App.tsx:87-99). Three states, all `3x8` padding,
 * full radius, 10sp/600:
 *
 * - **None** — slate `#E2E8F0` on `#475569`, reading "Company not identified".
 * - **Unverified** — amber→orange gradient (`#F59E0B`→`#F97316`), white, warning icon.
 * - **Verified** — `#2563EB`, white, a 16dp [CompanyBadge] then the name and a tick.
 *
 * The company name is never ellipsised; it wraps.
 */
@Composable
fun CompanyPill(
    name: String,
    verification: CompanyVerification,
    modifier: Modifier = Modifier,
) {
    val shape = MlxShape.Pill
    val content: @Composable () -> Unit = when {
        name.isBlank() || verification == CompanyVerification.None -> {
            {
                Icon(
                    Icons.Filled.Business,
                    contentDescription = null,
                    tint = Mlx.Text600,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "Company not identified",
                    style = MlxType.MicroPill,
                    color = Mlx.Text600,
                )
            }
        }

        verification == CompanyVerification.Unverified -> {
            {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Text(text = name, style = MlxType.MicroPill, color = Color.White)
            }
        }

        else -> {
            {
                CompanyBadge(name = name, size = 16.dp)
                Text(
                    text = "$name  \u2713",
                    style = MlxType.MicroPill,
                    color = Color.White,
                )
            }
        }
    }

    Row(
        modifier = modifier
            .then(
                if (verification == CompanyVerification.Unverified && name.isNotBlank()) {
                    Modifier.background(
                        Brush.horizontalGradient(listOf(Mlx.Amber500, Mlx.GuessSoft)),
                        shape,
                    )
                } else {
                    Modifier.background(
                        if (name.isBlank() || verification == CompanyVerification.None) {
                            Mlx.Brand200
                        } else {
                            Mlx.Brand500
                        },
                        shape,
                    )
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}
