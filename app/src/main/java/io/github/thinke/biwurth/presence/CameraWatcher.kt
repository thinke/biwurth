package io.github.thinke.biwurth.presence

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import io.github.thinke.biwurth.EventLog
import kotlin.math.abs

/**
 * Watches one camera for motion at a few frames a second: each frame's brightness, averaged
 * over a grid of [COLUMNS] × [ROWS] cells, is compared with a background that slowly follows
 * the picture, so light that changes gradually doesn't count. Reports, per frame, the share of
 * cells that differ by more than [CELL_DELTA] brightness levels, and the frame's mean brightness
 * (0-255). Frames are looked at and dropped: nothing is kept or sent.
 */
class CameraWatcher(
    private val context: Context,
    private val plan: Presence.CameraPlan,
    private val handler: Handler,
    private val onFrame: (change: Float, luma: Float) -> Unit,
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var background: FloatArray? = null
    private var lastFrame = 0L
    @Volatile private var stopped = false

    @SuppressLint("MissingPermission") // Presence checks it first.
    fun start() {
        val manager = context.getSystemService(CameraManager::class.java)
        val reader = ImageReader.newInstance(plan.width, plan.height, ImageFormat.YUV_420_888, 2).also { reader = it }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use(::analyse) }, handler)
        val fps = runCatching {
            manager.getCameraCharacteristics(plan.id)[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                .orEmpty().minWithOrNull(compareBy({ it.upper }, { it.lower }))
        }.getOrNull()
        runCatching {
            manager.openCamera(
                plan.id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (stopped) return camera.close()
                        device = camera
                        @Suppress("DEPRECATION") // The list form works back to Android 8.
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (stopped) return s.close()
                                    session = s
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(reader.surface)
                                        fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                    }
                                    runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                                        .onFailure { EventLog.w("presence", "Camera wouldn't start", it) }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) = EventLog.w("presence", "Camera session failed")
                            },
                            handler,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) = camera.close()
                    override fun onError(camera: CameraDevice, error: Int) {
                        EventLog.w("presence", "Camera error $error")
                        camera.close()
                    }
                },
                handler,
            )
        }.onFailure { EventLog.w("presence", "Can't open camera ${plan.id}", it) }
    }

    fun stop() {
        stopped = true
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            session = null
            device = null
            reader = null
        }
    }

    private fun analyse(image: Image) {
        val now = System.currentTimeMillis()
        // The camera may deliver more than asked for: look only plan.fps times a second.
        if (now - lastFrame < 1000L / plan.fps) return
        lastFrame = now
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val cells = FloatArray(COLUMNS * ROWS)
        val counts = IntArray(COLUMNS * ROWS)
        var total = 0L
        var n = 0
        // Every other pixel in each direction is plenty for cell averages.
        var y = 0
        while (y < height) {
            val row = y * ROWS / height * COLUMNS
            var x = 0
            while (x < width) {
                val v = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                val cell = row + x * COLUMNS / width
                cells[cell] += v.toFloat()
                counts[cell]++
                total += v
                n++
                x += 2
            }
            y += 2
        }
        for (i in cells.indices) if (counts[i] > 0) cells[i] /= counts[i]
        val bg = background ?: cells.copyOf().also { background = it }
        var changed = 0
        for (i in cells.indices) {
            val diff = cells[i] - bg[i]
            if (abs(diff) > CELL_DELTA) changed++
            // Follow the picture slowly: a person passing stands out, a sunset doesn't.
            bg[i] += diff * BACKGROUND_RATE
        }
        onFrame(changed.toFloat() / cells.size, if (n > 0) total.toFloat() / n else 0f)
    }

    private companion object {
        const val COLUMNS = 32
        const val ROWS = 24
        const val CELL_DELTA = 14f
        const val BACKGROUND_RATE = 0.08f
    }
}
