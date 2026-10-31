package io.github.thinke.biwurth

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.thinke.biwurth.update.SIGNING_FLAGS
import io.github.thinke.biwurth.update.Version
import io.github.thinke.biwurth.update.signers
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Setting up a fresh tablet as a biwurth panel from a QR code: tap the setup wizard's welcome
 * screen six times, scan, and Android downloads biwurth from GitHub, checks it's signed with
 * this key and makes it device owner. No adb and no Google account.
 */
object Provisioning {
    /** The QR payload Android's setup wizard reads; Wi-Fi only if given, so the tablet can download. */
    fun payload(context: Context, version: Version, wifiSsid: String?, wifiPassword: String?): JSONObject {
        val apk = "https://github.com/thinke/biwurth/releases/download/v$version/biwurth-$version.apk"
        return JSONObject()
            .put(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME",
                ComponentName(context, AdminReceiver::class.java).flattenToString(),
            )
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", apk)
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", signatureChecksum(context))
            .put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
            .apply {
                if (!wifiSsid.isNullOrBlank()) {
                    put("android.app.extra.PROVISIONING_WIFI_SSID", wifiSsid)
                    if (!wifiPassword.isNullOrEmpty()) {
                        put("android.app.extra.PROVISIONING_WIFI_PASSWORD", wifiPassword)
                        put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "WPA")
                    } else {
                        put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "NONE")
                    }
                }
            }
    }

    // SHA-256 of our signing certificate, URL-safe base64 without padding, as Android expects.
    // It's this build's key: a debug build gives a checksum no GitHub release matches.
    private fun signatureChecksum(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, SIGNING_FLAGS)
        val cert = info.signers().firstOrNull() ?: error("biwurth has no signing certificate")
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** [text] as a QR code bitmap, black on white with a quiet zone, for the tablet's own screens. */
    fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.RGB_565)
    }

    /** [text] as an SVG QR code, black on white with a quiet zone, for the website to show. */
    fun qrSvg(text: String): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val path = StringBuilder()
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) path.append("M$x ${y}h1v1h-1z")
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }
}

/** Android 12+ setup asks the device-owner app which mode it wants: a fully managed panel. */
class ProvisioningModeActivity : Activity() {
    // Only Android 12+ starts this (the constants are inlined); older Android needs no answer and
    // finishes through AdminReceiver.onProfileProvisioningComplete.
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            RESULT_OK,
            Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE),
        )
        finish()
    }
}

/** Android's setup hands over once biwurth is owner: apply the panel policies, then carry on. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.onRightsChanged(this)
        setResult(RESULT_OK)
        finish()
    }
}
