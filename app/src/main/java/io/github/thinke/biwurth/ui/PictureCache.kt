package io.github.thinke.biwurth.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import io.github.thinke.biwurth.EventLog
import java.io.File

/**
 * Pictures ready to draw, made once: SVGs rendered at their tile's pixel size (sharp, and as cheap
 * to draw as a photo), photos decoded no larger than needed. Compiling a layout fills it, so the
 * new layout's pictures are on screen the moment it switches. Least recently used go first.
 */
object PictureCache {
    private const val MAX_KB = 48 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    // Sizes a pixel or two apart (rounding in the layout) share an entry.
    private fun key(file: File, width: Int, height: Int, fit: String) =
        "${file.path}|${file.lastModified()}|${(width + 2) / 4}x${(height + 2) / 4}|$fit"

    /** What's ready for [file] at this size, without making it. */
    fun peek(file: File, width: Int, height: Int, fit: String): Bitmap? = cache.get(key(file, width, height, fit))

    /**
     * [file] ready for a [width] × [height] area: an SVG rendered to exactly that, fitted by [fit]
     * ("" shows all, "cover" fills, "stretch" fills exactly); a photo decoded to cover it. Slow the
     * first time; call off the main thread.
     */
    fun get(file: File, width: Int, height: Int, fit: String): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = key(file, width, height, fit)
        cache.get(key)?.let { return it }
        val bitmap = runCatching { if (file.name.endsWith(".svg")) renderSvg(file, width, height, fit) else decode(file, maxOf(width, height)) }
            .onFailure { EventLog.w("tile", "Can't draw the picture ${file.name}", it) }
            .getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * A built-in icon (assets/icons/<name>.svg, drawn in white so it can be tinted) at [size]
     * pixels square, or null when there's no such icon.
     */
    fun icon(context: android.content.Context, name: String, size: Int): Bitmap? {
        if (size <= 0) return null
        val key = "icon:$name|$size"
        cache.get(key)?.let { return it }
        val svg = runCatching { context.assets.open("icons/$name.svg").use(SVG::getFromInputStream) }.getOrNull() ?: return null
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
            svg.renderToCanvas(Canvas(b), RenderOptions().viewPort(0f, 0f, size.toFloat(), size.toFloat()))
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /** Kilobytes the cache holds for [files]: what a layout's pictures take. */
    fun kilobytes(files: Collection<File>): Int {
        val paths = files.map { it.path + "|" }
        return cache.snapshot().filterKeys { key -> paths.any(key::startsWith) }.values.sumOf { it.allocationByteCount / 1024 }
    }

    private fun renderSvg(file: File, width: Int, height: Int, fit: String): Bitmap {
        val svg = file.inputStream().use(SVG::getFromInputStream).apply {
            // Without a viewBox an SVG can't scale: make one from its own size.
            if (documentViewBox == null && documentWidth > 0 && documentHeight > 0) setDocumentViewBox(0f, 0f, documentWidth, documentHeight)
            // A fixed width and height would win over the tile's size.
            setDocumentWidth("100%")
            setDocumentHeight("100%")
        }
        val aspect = when (fit) {
            "cover" -> PreserveAspectRatio.FULLSCREEN
            "stretch" -> PreserveAspectRatio.STRETCH
            else -> PreserveAspectRatio.LETTERBOX
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            svg.renderToCanvas(Canvas(bitmap), RenderOptions().viewPort(0f, 0f, width.toFloat(), height.toFloat()).preserveAspectRatio(aspect))
        }
    }

    // The largest power-of-two reduction that still covers maxPixels: a 12-megapixel photo would
    // otherwise take ~48 MB for a 1280-pixel screen.
    private fun decode(file: File, maxPixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels.coerceAtLeast(1)) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
