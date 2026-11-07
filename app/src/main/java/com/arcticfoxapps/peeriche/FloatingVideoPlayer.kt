package com.arcticfoxapps.peeriche

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.roundToInt

internal fun clampFloatingPosition(position: Offset, maxX: Float, maxY: Float) = Offset(
    position.x.coerceIn(0f, maxX.coerceAtLeast(0f)), position.y.coerceIn(0f, maxY.coerceAtLeast(0f)))

@Composable internal fun FloatingVideoPlayer(video: Video, server: String, onExpand: () -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp)) {
        val width = minOf(240.dp, maxWidth)
        val height = width * 9 / 16
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - height).toPx().coerceAtLeast(0f) }
        var position by remember(video.apiId(), maxX, maxY) { mutableStateOf(Offset(maxX, maxY)) }
        var player by remember { mutableStateOf<Player?>(null) }
        var playing by remember { mutableStateOf(false) }
        DisposableEffect(player) {
            playing = player?.playWhenReady == true
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { playing = value }
            }
            player?.addListener(listener)
            onDispose { player?.removeListener(listener) }
        }
        Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .width(width).height(height).clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .pointerInput(video.apiId(), maxX, maxY) {
                detectDragGestures { change, delta -> change.consume(); position = clampFloatingPosition(position + delta, maxX, maxY) }
            }) {
            BackgroundVideoPlayer(video, server, Modifier.fillMaxSize(), pip = true, onController = { player = it })
            Row(Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = .35f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.OpenInFull, uiText("Открыть видео", "Open video"), tint = Color.White, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, uiText("Закрыть видео", "Close video"), tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            IconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.align(Alignment.BottomStart).size(40.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, uiText(if (playing) "Пауза" else "Воспроизвести", if (playing) "Pause" else "Play"), tint = Color.White)
            }
        }
    }
}
