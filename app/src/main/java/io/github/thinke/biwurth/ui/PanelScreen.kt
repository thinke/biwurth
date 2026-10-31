package io.github.thinke.biwurth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.Page
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The panel: the layout's pages, swiped sideways. Each page stretches the layout's grid over the
 * whole screen, whatever its size or orientation, and places each tile on its cells.
 * [currentPage] is shared with Home Assistant (MQTT), which can switch pages too.
 */
@Composable
fun PanelScreen(layout: Layout, currentPage: MutableStateFlow<Int>, onOpenSettings: () -> Unit) {
    val theme = PanelTheme.from(layout.theme)
    val pager = rememberPagerState(initialPage = currentPage.value) { layout.pages.size }

    // Follow page changes from outside (sleep, Home Assistant) and report swipes back.
    LaunchedEffect(pager) {
        currentPage.collect { page -> if (page in layout.pages.indices && page != pager.currentPage) pager.animateScrollToPage(page) }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { currentPage.value = it }
    }

    CompositionLocalProvider(
        LocalPanelTheme provides theme,
        LocalTextStyle provides panelTextStyle(layout.theme.font),
        LocalTextScale provides layout.theme.textScale,
    ) {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            // Keep every page composed: rebuilding one on each swipe made switching pages stutter.
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            beyondViewportPageCount = (layout.pages.size - 1).coerceAtLeast(0),
            key = { layout.pages.getOrNull(it)?.id ?: it },
        ) { index ->
                layout.pages.getOrNull(index)?.let { PageView(layout, it) }
            }
            if (layout.pages.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(layout.pages.size) { i ->
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (i == pager.currentPage) theme.muted else theme.muted.copy(alpha = 0.3f)),
                        )
                    }
                }
            }
            SettingsCorner(onOpenSettings)
        }
    }
}

@Composable
private fun PageView(layout: Layout, page: Page) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(GAP)) {
        val cellWidth = maxWidth / layout.columns
        val cellHeight = maxHeight / layout.rows
        page.tiles.forEach { tile ->
            key(tile.id) {
                Box(
                    Modifier
                        .offset(cellWidth * tile.x, cellHeight * tile.y)
                        .size(cellWidth * tile.w, cellHeight * tile.h)
                        .padding(GAP),
                ) {
                    TileView(tile)
                }
            }
        }
        if (page.tiles.isEmpty()) {
            Text(
                "Nothing on this page yet.\nTap the top-left corner five times for the website address.",
                Modifier.align(Alignment.Center),
                color = LocalPanelTheme.current.muted,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Five quick taps in the top-left corner open Settings, so a passer-by won't find it. */
@Composable
private fun SettingsCorner(onOpen: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var firstTap by remember { mutableLongStateOf(0L) }
    Box(
        Modifier.size(72.dp).pointerInput(Unit) {
            detectTapGestures {
                val now = System.currentTimeMillis()
                if (now - firstTap > 3_000) {
                    firstTap = now
                    taps = 0
                }
                if (++taps >= 5) {
                    taps = 0
                    onOpen()
                }
            }
        },
    )
}

private val GAP = 4.dp
