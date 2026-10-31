package io.github.thinke.biwurth.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.thinke.biwurth.BuildConfig
import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.Kiosk
import io.github.thinke.biwurth.MainActivity
import io.github.thinke.biwurth.Prefs
import io.github.thinke.biwurth.update.Releases.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String?) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState

    /** Handed to Android's installer; on a device-owner panel it installs without asking. */
    data class Installing(val release: Release) : UpdateState

    /** Android must first allow biwurth to install apps (a one-time switch on the tablet). */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Checks GitHub releases for a newer biwurth and installs it through Android's installer, after
 * checking the published SHA-256 and that the new APK is signed like the installed one. As SnapTV
 * does, but driven from the website and Home Assistant, since a wall panel has no one at it.
 */
class Updater(private val context: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installed: Version = Version.parse(BuildConfig.VERSION_NAME) ?: Version.parse("0")!!

    /** The newest release seen by the last check, for Home Assistant's update entity. */
    @Volatile var latest: Release? = null
        private set

    init {
        instance = this
        scope.launch {
            delay(60_000) // let the panel settle after boot
            while (true) {
                checkIfDue()
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    // Debug builds are signed with the debug key, so a release could never replace them.
    val canUpdate: Boolean get() = !BuildConfig.DEBUG

    /**
     * Whether an update installs with nobody at the tablet. Before Android 12 Android asks for every
     * update unless biwurth is device owner, so installing automatically would only put up the
     * installer's prompt over the panel again and again.
     */
    val installsSilently: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || Kiosk.isDeviceOwner(context)

    /** When the panel comes on screen, as SnapTV does on opening, unless we checked a few minutes ago. */
    fun checkOnStart() = checkIfOlderThan(START_MIN_INTERVAL_MS)

    private fun checkIfDue() = checkIfOlderThan(CHECK_INTERVAL_MS)

    private fun checkIfOlderThan(ms: Long) {
        if (!canUpdate || !prefs.updateCheck) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < ms) return
        scope.launch {
            check()
            val available = _state.value as? UpdateState.Available
            if (available != null && prefs.updateAutoInstall && installsSilently && !isSkipped(available.release)) {
                install(available.release)
            }
        }
    }

    suspend fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            try {
                val releases = Releases.parse(get(Releases.API_URL))
                prefs.lastUpdateCheck = System.currentTimeMillis()
                val pre = prefs.updatePrerelease
                latest = releases.firstOrNull { pre || !it.prerelease }
                val newest = Releases.newest(releases, installed, pre)
                EventLog.i("update", newest?.let { "Checked: ${it.version} available" } ?: "Checked: up to date")
                if (newest != null) {
                    UpdateState.Available(newest.copy(changes = Releases.changesSince(releases, installed, newest, pre)))
                } else {
                    UpdateState.UpToDate(latest?.version?.toString())
                }
            } catch (e: Exception) {
                EventLog.w("update", "Check failed: ${e.message}", e)
                UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun install(release: Release) {
        if (!canUpdate) {
            _state.value = UpdateState.Failed("This is a debug build: install a release once, then updates work.", release)
            return
        }
        // A device owner may install without the "unknown apps" switch.
        if (!Kiosk.isDeviceOwner(context) && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        scope.launch {
            try {
                val apk = download(release)
                verify(apk)
                _state.value = UpdateState.Installing(release)
                EventLog.i("update", "Installing ${release.version}")
                commit(apk)
            } catch (e: Exception) {
                EventLog.e("update", "Installing ${release.version} failed: ${e.message}", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    /** Opens the "install unknown apps" switch for biwurth on the tablet. */
    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun skip(release: Release) {
        prefs.skippedVersion = release.version.toString()
    }

    fun isSkipped(release: Release) = prefs.skippedVersion == release.version.toString()

    fun stateJson(): JSONObject {
        val json = JSONObject()
            .put("installed", installed.toString())
            .put("canUpdate", canUpdate)
            .put("deviceOwner", Kiosk.isDeviceOwner(context))
            .put("check", prefs.updateCheck)
            .put("prerelease", prefs.updatePrerelease)
            .put("autoInstall", prefs.updateAutoInstall)
            .put("silentInstall", installsSilently)
            .put("lastCheck", prefs.lastUpdateCheck)
        when (val s = _state.value) {
            UpdateState.Idle -> json.put("state", "idle")
            UpdateState.Checking -> json.put("state", "checking")
            is UpdateState.UpToDate -> json.put("state", "upToDate").put("latest", s.latest)
            is UpdateState.Available -> json.put("state", "available").put("release", s.release.toJson())
                .put("skipped", isSkipped(s.release))
            is UpdateState.Downloading -> json.put("state", "downloading").put("release", s.release.toJson()).put("fraction", s.fraction.toDouble())
            is UpdateState.Installing -> json.put("state", "installing").put("release", s.release.toJson())
            is UpdateState.NeedsPermission -> json.put("state", "needsPermission").put("release", s.release.toJson())
            is UpdateState.Failed -> json.put("state", "failed").put("message", s.message)
                .apply { s.release?.let { put("release", it.toJson()) } }
        }
        return json
    }

    private fun download(release: Release): File {
        val expected = Releases.parseSha256(get(release.sha256Url)) ?: error("The release has no valid checksum file")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "biwurth-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.apkUrl)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error("The download is corrupt (checksum mismatch). Try again.")
        }
        return file
    }

    /** Same package, same signing certificate, not a downgrade. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, SIGNING_FLAGS) ?: error("The download is not a valid app")
        val current = pm.getPackageInfo(context.packageName, SIGNING_FLAGS)
        if (archive.packageName != context.packageName) error("The download is a different app (${archive.packageName})")
        val archiveCerts = certs(archive)
        val currentCerts = certs(current)
        // No certificates on either side would make the sets equal without proving anything.
        if (archiveCerts.isEmpty() || currentCerts.isEmpty()) error("Couldn't read the download's signature, so it was not installed")
        if (archiveCerts != currentCerts) error("The download is not signed by the same key as this biwurth, so it was not installed")
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(current)) {
            error("The download is older than the installed version")
        }
    }

    private fun certs(p: PackageInfo): Set<String> =
        p.signers().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Updating ourselves needs no confirmation where Android allows it (device owner, or
            // once biwurth installed itself); otherwise Android asks on the tablet. Before Android 12
            // there is no such switch: a device owner's session installs silently anyway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("biwurth.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable because PackageInstaller fills in the result extras; before Android 12
            // PendingIntents are mutable by default and the flag doesn't exist.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(intent.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle // we are about to be restarted anyway
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed("Android didn't install the update: ${message ?: "error $status"}", release)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "biwurth/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} from GitHub")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "biwurth.Update"
        private const val CHECK_INTERVAL_MS = 20 * 60 * 60 * 1000L
        private const val START_MIN_INTERVAL_MS = 10 * 60 * 1000L
        @Volatile internal var instance: Updater? = null
    }
}

/** PackageManager flags that fill in [signers]: signingInfo exists from Android 9, signatures before. */
@Suppress("DEPRECATION")
internal val SIGNING_FLAGS: Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

/** The certificates the APK is signed with, read as [SIGNING_FLAGS] asked for them. */
@Suppress("DEPRECATION")
internal fun PackageInfo.signers(): List<Signature> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners.orEmpty().toList() else signatures.orEmpty().toList()

/** Receives PackageInstaller's result; when it needs the user's OK, shows Android's confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
            return
        }
        Updater.instance?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/**
 * Android restarts biwurth after it updates itself, which ends kiosk mode and leaves the system
 * launcher on screen; come straight back instead.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
