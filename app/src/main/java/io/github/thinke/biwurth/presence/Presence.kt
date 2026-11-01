package io.github.thinke.biwurth.presence

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.github.thinke.biwurth.AdminReceiver
import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.Kiosk
import io.github.thinke.biwurth.PanelApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Noticing people in front of the panel, with what this tablet has (see [Inventory]): motion in
 * the front camera, the room's brightness from the same camera when there's no light sensor,
 * taps and knocks in the accelerometer, and light and proximity sensors where there are some.
 * Calibration measures each on this tablet, in its place on the wall, and writes a [Plan].
 *
 * Runs only while the panel is in front ([foreground]): Android blocks the camera for apps in
 * the background, so it works with the "Backlight off" sleep mode, not with "Screen off".
 */
class Presence(private val app: PanelApp) {
    /** Calibrated settings for each detector; null detectors are off. */
    data class Plan(
        val enabled: Boolean = false,
        val camera: CameraPlan? = null,
        val touch: TouchPlan? = null,
        val lightSensor: Boolean = false,
        val proximitySensor: Boolean = false,
        /** Movement keeps an awake panel awake, not only wakes a sleeping one. */
        val keepAwake: Boolean = true,
        /** -2 (only clear motion) to 2 (the slightest): scales the calibrated thresholds. */
        val sensitivity: Int = 0,
        val notes: List<String> = emptyList(),
        val calibrated: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled).put("keepAwake", keepAwake).put("sensitivity", sensitivity)
            .put("camera", camera?.toJson() ?: JSONObject.NULL)
            .put("touch", touch?.let { JSONObject().put("threshold", it.threshold.toDouble()) } ?: JSONObject.NULL)
            .put("lightSensor", lightSensor).put("proximitySensor", proximitySensor)
            .put("notes", JSONArray(notes))
            .put("calibrated", calibrated ?: JSONObject.NULL)

        companion object {
            fun fromJson(json: JSONObject) = Plan(
                enabled = json.optBoolean("enabled"),
                keepAwake = json.optBoolean("keepAwake", true),
                sensitivity = json.optInt("sensitivity").coerceIn(-2, 2),
                camera = json.optJSONObject("camera")?.let(CameraPlan::fromJson),
                touch = json.optJSONObject("touch")?.let { TouchPlan(it.optDouble("threshold", 0.2).toFloat()) },
                lightSensor = json.optBoolean("lightSensor"),
                proximitySensor = json.optBoolean("proximitySensor"),
                notes = json.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                calibrated = json.optString("calibrated").ifBlank { null },
            )
        }
    }

    /** Camera motion: [threshold] is the share of the picture that must change, [frames] in a row. */
    data class CameraPlan(val id: String, val width: Int, val height: Int, val fps: Int, val threshold: Float, val frames: Int) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("width", width).put("height", height)
            .put("fps", fps).put("threshold", threshold.toDouble()).put("frames", frames)

        companion object {
            fun fromJson(json: JSONObject) = CameraPlan(
                json.getString("id"), json.optInt("width", 176), json.optInt("height", 144), json.optInt("fps", 3).coerceIn(1, 10),
                json.optDouble("threshold", 0.02).toFloat(), json.optInt("frames", 2).coerceIn(1, 5),
            )
        }
    }

    /** A tap or knock: how far the acceleration must jump from its usual level, in m/s². */
    data class TouchPlan(val threshold: Float)

    private val file = File(app.filesDir, "presence.json")
    private val _plan = MutableStateFlow(runCatching { Plan.fromJson(JSONObject(file.readText())) }.getOrDefault(Plan()))
    val plan: StateFlow<Plan> = _plan
    val inventory: Inventory by lazy { Inventory.of(app) }

    // Live readings, for the website and Home Assistant.
    val motion = MutableStateFlow(false)
    @Volatile var lastMotion = 0L
        private set
    @Volatile var lastSource = ""
        private set
    @Volatile var score = 0f
        private set
    /** The room's brightness, 0-100, from the light sensor or else the camera; null until known. */
    val brightness = MutableStateFlow<Int?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val thread by lazy { HandlerThread("presence").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val sensors by lazy { app.getSystemService(SensorManager::class.java) }
    private var camera: CameraWatcher? = null
    private var foreground = false
    private var calibrating = false
    private var consecutive = 0
    private var gravity = 0f
    private var motionOff: Runnable? = null

    // Samples while calibrating: camera change, camera brightness, acceleration jumps.
    private val samples = Samples()
    private class Samples {
        val change = mutableListOf<Float>()
        val luma = mutableListOf<Float>()
        val shake = mutableListOf<Float>()
        fun clear() { change.clear(); luma.clear(); shake.clear() }
    }
    private val calibration = Mutex()
    private var still: JSONObject? = null
    private var moving: JSONObject? = null

    /** The panel came to the front (true) or left it. */
    fun setForeground(value: Boolean) {
        foreground = value
        refresh()
    }

    fun update(change: (Plan) -> Plan) {
        save(change(_plan.value))
        refresh()
    }

    /** Whether the detectors should run now: on, or calibrating, with the panel in front. */
    private fun refresh() = main.post {
        val p = _plan.value
        val run = foreground && (p.enabled || calibrating)
        val cameraPlan = if (calibrating) p.camera ?: defaultCamera() else activeCamera()
        if (run && cameraPlan != null && camera == null && cameraAllowed()) {
            consecutive = 0
            camera = CameraWatcher(app, cameraPlan, handler, ::onFrame).also { it.start() }
        } else if ((!run || cameraPlan == null) && camera != null) {
            camera?.stop()
            camera = null
        }
        sensors.unregisterListener(listener)
        if (run) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler) }
            if (p.lightSensor) sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            if (p.proximitySensor) sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        }
    }

    val running get() = camera != null

    // ---- Detection ---------------------------------------------------------------------------

    private fun multiplier() = 2.0.pow(-_plan.value.sensitivity / 2.0).toFloat()

    private fun onFrame(change: Float, luma: Float) {
        score = change
        if (!_plan.value.lightSensor) brightness.value = (luma / 255f * 100).toInt()
        if (calibrating) {
            samples.change += change
            samples.luma += luma
            return
        }
        val p = activeCamera() ?: return
        consecutive = if (change > p.threshold * multiplier()) consecutive + 1 else 0
        if (consecutive >= p.frames) {
            consecutive = 0
            detected("camera")
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = event.values
                    val magnitude = sqrt(x * x + y * y + z * z)
                    if (gravity == 0f) gravity = magnitude
                    val jump = abs(magnitude - gravity)
                    gravity += (magnitude - gravity) * 0.1f
                    if (calibrating) samples.shake += jump
                    else _plan.value.touch?.let { if (jump > it.threshold * multiplier()) detected("touch") }
                }
                // A light sensor reads lux; 400 lux and more counts as fully bright.
                Sensor.TYPE_LIGHT -> brightness.value = (event.values[0] / 4f).toInt().coerceIn(0, 100)
                Sensor.TYPE_PROXIMITY -> if (event.values[0] < event.sensor.maximumRange && !calibrating) detected("proximity")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun detected(source: String) {
        lastMotion = System.currentTimeMillis()
        lastSource = source
        main.post {
            if (!motion.value) motion.value = true
            app.screen.motion(_plan.value.keepAwake)
            // Motion stays "on" until half a minute passes without any.
            motionOff?.let(main::removeCallbacks)
            motionOff = Runnable { motion.value = false }.also { main.postDelayed(it, MOTION_HOLD_MS) }
        }
    }

    // ---- Calibration -------------------------------------------------------------------------

    /**
     * Measures for [seconds]: [step] "still" (nobody moving in view) or "moving" (someone walking
     * in front, waving, tapping the panel). After each step the plan is made again from what's
     * been measured. The panel must be in front, so the camera can run.
     */
    suspend fun calibrate(step: String, seconds: Int): JSONObject = calibration.withLock {
        require(step == "still" || step == "moving") { "step is still or moving" }
        check(foreground) { "The panel isn't on screen (Settings or another app is in front)" }
        check(cameraAllowed() || inventory.cameras.isEmpty()) { "biwurth may not use the camera: allow it in Android's app settings" }
        calibrating = true
        samples.clear()
        refresh()
        try {
            delay(1_500) // let the camera settle its exposure
            samples.clear()
            delay(seconds * 1_000L)
            val stats = JSONObject()
                .put("seconds", seconds)
                .put("frames", samples.change.size)
                .put("change", summary(samples.change))
                .put("brightness", summary(samples.luma.map { it / 255f * 100 }))
                .put("shake", summary(samples.shake))
            if (step == "still") still = stats else moving = stats
            save(makePlan())
            EventLog.i("presence", "Calibrated ($step): ${samples.change.size} frames")
            stats
        } finally {
            calibrating = false
            refresh()
        }
    }

    /** The calibration so far: inventory, what each step measured, and the plan made from it. */
    fun calibrationJson(): JSONObject = JSONObject()
        .put("still", still ?: JSONObject.NULL)
        .put("moving", moving ?: JSONObject.NULL)

    private fun makePlan(): Plan {
        val inv = inventory
        val old = _plan.value
        val notes = mutableListOf<String>()
        val stillChange = still?.optJSONObject("change")
        val movingChange = moving?.optJSONObject("change")
        val camera = (old.camera ?: defaultCamera())?.let { base ->
            // Noise: how much changes with nobody there. Motion: how much changed while someone moved.
            val noise = (stillChange?.optDouble("p99") ?: 0.004).toFloat().coerceAtLeast(0.002f)
            val signal = movingChange?.optDouble("p75")?.toFloat()
            val threshold = when {
                signal == null -> (noise * 4).coerceAtLeast(0.01f)
                signal > noise * 3 -> sqrt(noise * signal)
                else -> (noise * 2).coerceAtLeast(0.01f)
            }.coerceAtMost(MAX_THRESHOLD)
            // A picture that keeps changing on its own (a TV, a screen, flicker) hides people.
            if (noise > NOISY) {
                notes += "The picture changes a lot by itself (${pct(noise)} with nobody there): something moving " +
                    "or flickering is in view, so motion can't be told apart. Camera motion is off; point the panel elsewhere or remove what moves."
            }
            val bright = still?.optJSONObject("brightness")?.optDouble("p50")
            val c = inv.cameras.firstOrNull { it.id == base.id }
            if (noise <= NOISY) notes += "${c?.facing?.replaceFirstChar(Char::uppercase) ?: "Camera"} camera ${base.id} " +
                "(${base.width}×${base.height}): looks ${base.fps} times a second; motion when ${pct(threshold)} of the picture " +
                "changes in ${base.frames} frames in a row" +
                (if (stillChange != null) " (with nobody there ${pct(noise)} changed" else "") +
                (if (signal != null) ", with someone moving ${pct(signal)})." else if (stillChange != null) ")." else ".")
            if (noise > NOISY) Unit
            else if (signal == null) notes += "Walk in front of the panel for the second step to set this from real motion."
            else if (signal <= noise * 3) notes += "Motion hardly stood out from the camera's noise: more light, or a panel facing where people walk, would help."
            if (bright != null && bright < 12) notes += "The room looked dark (${bright.toInt()} % brightness): the camera can't see motion in the dark."
            base.copy(threshold = threshold)
        }?.takeIf { (still?.optJSONObject("change")?.optDouble("p99") ?: 0.0) <= NOISY }
        if (camera == null && inv.frontCamera == null) notes += "No camera this can use: motion needs a front camera."
        if (!inv.has(Sensor.TYPE_LIGHT)) notes += if (camera != null) "No light sensor: the room's brightness comes from the camera." else "No light sensor."
        val touch = if (inv.has(Sensor.TYPE_ACCELEROMETER)) {
            val noise = (still?.optJSONObject("shake")?.optDouble("p99") ?: 0.05).toFloat()
            val threshold = (noise * 5).coerceAtLeast(0.15f)
            notes += "Accelerometer: a tap or knock on the panel wakes it (a jump over ${"%.2f".format(threshold)} m/s²)."
            TouchPlan(threshold)
        } else {
            null
        }
        val light = inv.has(Sensor.TYPE_LIGHT)
        val proximity = inv.has(Sensor.TYPE_PROXIMITY)
        if (light) notes += "Light sensor: the room's brightness."
        if (proximity) notes += "Proximity sensor: a hand near the panel wakes it." else notes += "No proximity sensor."
        return old.copy(
            camera = camera, touch = touch, lightSensor = light, proximitySensor = proximity,
            notes = notes, calibrated = Instant.now().toString(),
        )
    }

    /**
     * The camera plan in use: the calibrated one, or before any calibration general settings for
     * the front camera. A calibration may leave the camera off (no plan), and then it stays off.
     */
    private fun activeCamera(): CameraPlan? = _plan.value.let { if (it.calibrated == null) it.camera ?: defaultCamera() else it.camera }

    private fun defaultCamera(): CameraPlan? {
        val c = inventory.frontCamera ?: return null
        val size = c.smallest ?: return null
        return CameraPlan(c.id, size.width, size.height, fps = 3, threshold = 0.02f, frames = 2)
    }

    private fun summary(values: List<Float>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("n", 0)
        val sorted = values.sorted()
        fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()].toDouble()
        return JSONObject().put("n", sorted.size).put("p50", p(0.5)).put("p75", p(0.75)).put("p99", p(0.99)).put("max", sorted.last().toDouble())
    }

    private fun pct(value: Float) = "%.1f %%".format(value * 100)

    // ---- Permission and storage --------------------------------------------------------------

    /** The camera permission; as device owner, biwurth grants it to itself. */
    fun cameraAllowed(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        if (!Kiosk.isDeviceOwner(app)) return false
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        return runCatching {
            dpm.setPermissionGrantState(
                ComponentName(app, AdminReceiver::class.java), app.packageName, Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.getOrDefault(false)
    }

    private fun save(plan: Plan) {
        _plan.value = plan
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(plan.toJson().toString(2))
        tmp.renameTo(file)
    }

    fun liveJson(): JSONObject = JSONObject()
        .put("running", running)
        .put("motion", motion.value)
        .put("lastMotion", if (lastMotion > 0) Instant.ofEpochMilli(lastMotion).toString() else JSONObject.NULL)
        .put("lastSource", lastSource.ifEmpty { JSONObject.NULL })
        .put("change", Math.round(score * 1000) / 10.0)
        .put("brightness", brightness.value ?: JSONObject.NULL)
        .put("cameraAllowed", inventory.cameras.isEmpty() || app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)

    private companion object {
        const val MOTION_HOLD_MS = 30_000L
        // Never ask for more than this share of the picture to change, and past this much change
        // with nobody there the camera can't tell people from what's moving anyway.
        const val MAX_THRESHOLD = 0.5f
        const val NOISY = 0.3
    }
}
