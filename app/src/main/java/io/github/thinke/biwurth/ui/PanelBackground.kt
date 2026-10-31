package io.github.thinke.biwurth.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.github.thinke.biwurth.config.Background
import io.github.thinke.biwurth.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** What's behind the pages: the theme's colour, one colour, a gradient or a picture. */
@Composable
fun PanelBackground(background: Background, theme: PanelTheme) {
    Box(Modifier.fillMaxSize().background(theme.background)) {
        when (background.type) {
            "color" -> Box(Modifier.fillMaxSize().background(parse(background.color) ?: theme.background))
            "gradient" -> Gradient(background)
            "image" -> background.image?.let { Picture(it, background.mode) }
        }
        if (background.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = background.dim / 100f)))
    }
}

@Composable
private fun Gradient(background: Background) {
    val colors = background.colors.mapNotNull(::parse).takeIf { it.size >= 2 } ?: return
    // Animated, the direction turns once every two minutes. Ten steps a second is smooth at that
    // speed and spares this tablet's GPU a full-screen redraw on every frame.
    var turn by remember { mutableFloatStateOf(0f) }
    if (background.animate) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(100)
                turn = (turn + 0.3f) % 360f
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val radians = Math.toRadians((background.angle + turn).toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        // Reach the corners whatever the angle: half the diagonal along the direction.
        val half = (kotlin.math.abs(dx) * size.width + kotlin.math.abs(dy) * size.height) / 2f
        val start = Offset(center.x - dx * half, center.y - dy * half)
        val end = Offset(center.x + dx * half, center.y + dy * half)
        drawRect(Brush.linearGradient(colors, start, end))
    }
}

@Composable
private fun Picture(name: String, mode: String) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tiles keep the picture's own size; everything else needs no more pixels than the screen.
        val maxPixels = with(LocalDensity.current) { maxOf(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        var bitmap by remember(name, mode) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(name, mode, maxPixels) {
            val file = context.panel.backgrounds.file(name) ?: return@LaunchedEffect
            bitmap = withContext(Dispatchers.IO) { decode(file, if (mode == "tile" || mode == "center") Int.MAX_VALUE else maxPixels) }
        }
        val image = bitmap?.asImageBitmap() ?: return@BoxWithConstraints
        when (mode) {
            "tile" -> Canvas(Modifier.fillMaxSize()) {
                drawRect(ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)))
            }
            else -> Image(
                image,
                contentDescription = null,
                contentScale = when (mode) {
                    "fit" -> ContentScale.Fit
                    "stretch" -> ContentScale.FillBounds
                    "center" -> ContentScale.None
                    else -> ContentScale.Crop
                },
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// Decodes at the largest power-of-two reduction that still covers maxPixels: a 12-megapixel
// photo would otherwise take ~48 MB of this 4 GB tablet's memory for an 1280-pixel screen.
private fun decode(file: File, maxPixels: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun parse(hex: String): Color? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
