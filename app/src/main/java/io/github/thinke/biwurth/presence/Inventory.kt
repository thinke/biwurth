package io.github.thinke.biwurth.presence

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** What this tablet has for noticing people: its cameras, sensors and microphone. */
class Inventory(val cameras: List<Camera>, val sensors: List<SensorInfo>, val microphone: Boolean) {
    class Camera(val id: String, val facing: String, val smallest: Size?, val slowestFps: Range<Int>?)
    class SensorInfo(val type: Int, val kind: String, val name: String, val vendor: String, val wakeUp: Boolean)

    val frontCamera get() = cameras.firstOrNull { it.facing == "front" && it.smallest != null }
    fun has(type: Int) = sensors.any { it.type == type }

    fun toJson(): JSONObject = JSONObject()
        .put(
            "cameras",
            JSONArray(
                cameras.map { c ->
                    JSONObject().put("id", c.id).put("facing", c.facing)
                        .put("smallest", c.smallest?.let { "${it.width}×${it.height}" } ?: JSONObject.NULL)
                        .put("slowestFps", c.slowestFps?.let { "${it.lower}-${it.upper}" } ?: JSONObject.NULL)
                },
            ),
        )
        .put(
            "sensors",
            JSONArray(sensors.map { JSONObject().put("kind", it.kind).put("name", it.name).put("vendor", it.vendor).put("wakeUp", it.wakeUp) }),
        )
        .put("microphone", microphone)

    companion object {
        /** Frames a little larger than this are plenty to see someone move, and cheap to look at. */
        private const val TARGET_PIXELS = 160 * 120

        fun of(context: Context): Inventory {
            val manager = context.getSystemService(CameraManager::class.java)
            val cameras = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList()).mapNotNull { id ->
                runCatching {
                    val c = manager.getCameraCharacteristics(id)
                    val facing = when (c[CameraCharacteristics.LENS_FACING]) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "external"
                    }
                    val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                    val smallest = sizes.filter { it.width * it.height >= TARGET_PIXELS }.minByOrNull { it.width * it.height }
                        ?: sizes.maxByOrNull { it.width * it.height }
                    val fps = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty().minWithOrNull(compareBy({ it.upper }, { it.lower }))
                    Camera(id, facing, smallest, fps)
                }.getOrNull()
            }
            val sensors = context.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
                .filter { it.type in KINDS }
                .distinctBy { it.type to it.isWakeUpSensor }
                .map { SensorInfo(it.type, KINDS.getValue(it.type), it.name, it.vendor, it.isWakeUpSensor) }
            val microphone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            return Inventory(cameras, sensors, microphone)
        }

        // The sensors that can tell something about people nearby (others are left out).
        val KINDS = mapOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PROXIMITY to "proximity",
            Sensor.TYPE_SIGNIFICANT_MOTION to "significant motion",
            Sensor.TYPE_MOTION_DETECT to "motion detect",
            Sensor.TYPE_STATIONARY_DETECT to "stationary detect",
        )
    }
}
