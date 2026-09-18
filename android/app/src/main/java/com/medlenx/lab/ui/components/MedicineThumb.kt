package com.medlenx.lab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape

/**
 * A medicine's pack photo, resolved from its **name** so it can be dropped in anywhere a
 * medicine is mentioned — Hub lists, recent prescriptions, Rx Audit rows, scan cards.
 *
 * Pass [imageUrl] when the caller already has the catalogue row (it skips the lookup).
 * Otherwise the name is resolved through [com.medlenx.lab.data.repo.MedicineImageStore]:
 * bundled catalogue first, live medex.com.bd as a fallback. Until something resolves, or
 * if nothing ever does, the pill icon stands in — so a row without a photo still looks
 * deliberate rather than broken.
 */
@Composable
fun MedicineThumb(
    name: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    size: Dp = 40.dp,
) {
    // LocalContext.current is itself a @Composable call, so it has to be read in the
    // composable body. Reading it inside the remember {} lambda is illegal: that
    // lambda is a plain calculation, not a composable context.
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as MedLenXApp).graph.medicineImages
    }

    var resolved by remember(name, imageUrl) {
        mutableStateOf(imageUrl?.takeIf { it.isNotBlank() })
    }

    LaunchedEffect(name, imageUrl) {
        if (!imageUrl.isNullOrBlank()) return@LaunchedEffect
        resolved = runCatching { store.imageFor(name) }.getOrNull() ?: resolved
    }

    Box(
        modifier = modifier
            .size(size)
            .background(Mlx.Brand50, MlxShape.Small),
        contentAlignment = Alignment.Center,
    ) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = "Pack image of $name",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Medication,
                contentDescription = null,
                tint = Mlx.Brand400,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
