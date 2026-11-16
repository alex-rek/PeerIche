package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable internal fun AccountNavigationIcon(avatar: String?) {
    var loaded by remember(avatar) { mutableStateOf(false) }
    val label = stringResource(R.string.you)
    Box(Modifier.size(24.dp).clip(CircleShape).semantics { contentDescription = label }) {
        if (!loaded) Icon(painterResource(R.drawable.ic_nav_you), null, Modifier.fillMaxSize())
        if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
