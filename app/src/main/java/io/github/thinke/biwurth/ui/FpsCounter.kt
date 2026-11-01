package io.github.thinke.biwurth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thinke.biwurth.FrameStats
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings → FPS counter: frames the panel drew in the last second, the median and 90th
 * percentile frame time, and the share of frames over the display's budget. Updating it is one
 * small redraw a second, so an idle panel reads one frame more than it otherwise draws.
 */
@Composable
fun FpsCounter(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val s = FrameStats.summary(System.nanoTime() - 1_000_000_000L)
            text = "${s.fps.roundToInt()} fps · ${"%.1f".format(s.percentileMs(0.5))} / ${"%.1f".format(s.percentileMs(0.9))} ms" +
                if (s.frames > 0) " · ${s.overBudget.roundToInt()} % slow" else ""
        }
    }
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.padding(8.dp).background(Color(0xB0000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
