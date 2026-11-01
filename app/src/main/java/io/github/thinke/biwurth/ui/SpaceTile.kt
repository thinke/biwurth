package io.github.thinke.biwurth.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.thinke.biwurth.config.PictureStore
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Empty space: holds its cells and shows nothing, or a picture. SVG is rendered at the tile's
 * exact pixel size, so it stays sharp from 1×1 up; other pictures are decoded no larger than needed.
 * Only the tile's own Opacity fades it (the panel's tile opacity is for card backgrounds).
 */
@Composable
fun SpaceTile(tile: Tile) {
    val name = tile.prop("picture") ?: return
    val file = LocalContext.current.panel.pictures.file(name) ?: return
    val fit = tile.prop("fit").orEmpty()
    val alpha = tile.prop("opacity")?.toIntOrNull()?.coerceIn(0, 100)?.div(100f) ?: 1f
    val svg = PictureStore.isSvg(name)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().graphicsLayer { this.alpha = alpha }) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Usually ready: compiling the layout made it at this size.
        var bitmap by remember(file, width, height, fit) { mutableStateOf(PictureCache.peek(file, width, height, if (svg) fit else "")) }
        LaunchedEffect(file, width, height, fit) {
            if (bitmap == null) bitmap = withContext(Dispatchers.IO) { PictureCache.get(file, width, height, if (svg) fit else "") }
        }
        val image = bitmap ?: return@BoxWithConstraints
        Image(
            image.asImageBitmap(),
            contentDescription = null,
            // An SVG is already fitted to the tile; a photo is fitted here.
            contentScale = when {
                svg -> ContentScale.FillBounds
                fit == "cover" -> ContentScale.Crop
                fit == "stretch" -> ContentScale.FillBounds
                else -> ContentScale.Fit
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
