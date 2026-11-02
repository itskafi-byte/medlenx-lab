package com.medlenx.lab.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import java.net.URLEncoder
import com.medlenx.lab.R
import com.medlenx.lab.ui.theme.Mlx

/** Whitelist the wire IDs rather than resolving arbitrary resource names. */
fun avatarResource(id: String?): Int? = when (id) {
    "avatar_01" -> R.drawable.avatar_01
    "avatar_02" -> R.drawable.avatar_02
    "avatar_03" -> R.drawable.avatar_03
    "avatar_04" -> R.drawable.avatar_04
    "avatar_05" -> R.drawable.avatar_05
    "avatar_06" -> R.drawable.avatar_06
    "avatar_07" -> R.drawable.avatar_07
    "avatar_08" -> R.drawable.avatar_08
    else -> null
}

/** Photos remain local; generated fallbacks send the display name to ui-avatars.com. */
@Composable
fun OfficerAvatar(imageUri: String?, size: Dp, modifier: Modifier = Modifier,
    avatarId: String? = null, fallbackName: String? = null) {
    val resource = avatarResource(avatarId)
    var photoFailed by remember(imageUri) { mutableStateOf(false) }
    val generated = fallbackName?.takeIf { it.isNotBlank() }?.let {
        val encodedName = URLEncoder.encode(it, "UTF-8")
        "https://ui-avatars.com/api/?name=$encodedName&background=random&bold=true"
    }
    Box(modifier.size(size).clip(CircleShape).background(Mlx.Brand900)
        .semantics { contentDescription = "Profile avatar" }, contentAlignment = Alignment.Center) {
        when {
            !imageUri.isNullOrBlank() && !photoFailed -> AvatarImage(imageUri, size, onError = { photoFailed = true })
            resource != null -> Image(painterResource(resource), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            generated != null -> AvatarImage(generated, size)
            else -> AvatarFallback(size)
        }
    }
}

@Composable
private fun AvatarImage(model: String, size: Dp, onError: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AvatarFallback(size)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(model)
                .transformations(CircleCropTransformation()).build(),
            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onError = { onError() },
        )
    }
}

@Composable
private fun AvatarFallback(size: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}
