package com.arcticfoxapps.peeriche

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable internal fun BrandLogo(modifier: Modifier = Modifier) {
    // Follow the active app palette, including manual and Aurora themes.
    val resource = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        R.drawable.peeriche_logo_dark else R.drawable.peeriche_logo_light
    Image(painterResource(resource), stringResource(R.string.app_name), modifier,
        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
}
