package io.github.thinke.biwurth.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.thinke.biwurth.config.Layout
import io.github.thinke.biwurth.config.Orientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Screenshots of a page as the panel draws it, made off-screen: the page is composed on a private
 * virtual display the size of this tablet's screen (turned to the layout's orientation) and read
 * back from it. Works for a layout that isn't saved, and while the screen is dimmed or off,
 * without touching what the panel shows. Cameras show their snapshot, not the live stream.
 */
object PanelSnapshot {
    class Options(val scale: Float = 1f, val jpeg: Boolean = false, val settleMs: Long = 2_500L)

    // One at a time: each is a whole extra screen's worth of drawing.
    private val mutex = Mutex()

    suspend fun capture(context: Context, layout: Layout, pageIndex: Int, options: Options = Options()): ByteArray = mutex.withLock {
        val page = layout.pages.getOrNull(pageIndex) ?: throw IllegalArgumentException("No page $pageIndex: the layout has ${layout.pages.size}")
        val (width, height) = size(context, layout.orientation)
        val frames = Frames()
        val session = withContext(Dispatchers.Main) {
            Session(context, width, height, frames).also { it.show { PanelPreview(layout, page) } }
        }
        try {
            // Long enough for fonts, cached history and snapshots to arrive and text to settle.
            delay(options.settleMs)
            val bitmap = frames.take() ?: throw IllegalStateException("Nothing was drawn")
            encode(bitmap, options)
        } finally {
            // Let go of the held frame before its reader closes.
            frames.close()
            withContext(Dispatchers.Main) { session.close() }
        }
    }

    /** This tablet's screen in pixels, turned to [orientation] (the editor's Auto: as it's held now). */
    private fun size(context: Context, orientation: Orientation): Pair<Int, Int> {
        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val portrait = when (orientation) {
            Orientation.PORTRAIT, Orientation.REVERSE_PORTRAIT -> true
            Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE -> false
            Orientation.AUTO -> context.resources.displayMetrics.let { it.heightPixels > it.widthPixels }
        }
        return if (portrait) short to long else long to short
    }

    private fun encode(bitmap: Bitmap, options: Options): ByteArray {
        val scale = options.scale.coerceIn(0.1f, 1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(if (options.jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 85, out)
                out.toByteArray()
            }
        } finally {
            // A screen-sized bitmap lives in native memory, which the Java heap's rare collections
            // free only late: let it go now so a run of screenshots doesn't pile them up.
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    /** The newest frame the virtual display drew; older ones are let go as new ones arrive. */
    private class Frames {
        private val thread = HandlerThread("panel-snapshot").apply { start() }
        val handler = Handler(thread.looper)
        private var latest: Image? = null
        private var closed = false

        fun offer(image: Image) = synchronized(this) {
            if (closed) {
                image.close()
            } else {
                latest?.close()
                latest = image
            }
        }

        fun take(): Bitmap? = synchronized(this) { latest?.let(::toBitmap) }

        fun close() {
            synchronized(this) {
                closed = true
                latest?.close()
                latest = null
            }
            thread.quitSafely()
        }

        // Rows can be padded past the width: copy whole rows, then crop.
        private fun toBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer.rewind())
            if (rowPixels == image.width) return padded
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }
    }

    /** The virtual display, its window and the composition in it; created and closed on the main thread. */
    private class Session(context: Context, width: Int, height: Int, private val frames: Frames) : LifecycleOwner, SavedStateRegistryOwner {
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        private val display: VirtualDisplay
        private val presentation: Presentation
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.let(frames::offer) }, frames.handler)
            // Private (no PUBLIC flag) and showing only our own window.
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "biwurth-preview", width, height, context.resources.displayMetrics.densityDpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            presentation = Presentation(context, display.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            savedState.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
            val view = ComposeView(presentation.context).apply {
                setViewTreeLifecycleOwner(this@Session)
                setViewTreeSavedStateRegistryOwner(this@Session)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent(content)
            }
            presentation.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            presentation.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            presentation.show()
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun close() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            runCatching { presentation.dismiss() }
            display.release()
            reader.close()
        }
    }
}
