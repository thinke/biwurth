package io.github.thinke.biwurth.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.thinke.biwurth.EventLog
import io.github.thinke.biwurth.ScreenState
import io.github.thinke.biwurth.config.Tile
import io.github.thinke.biwurth.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * A live camera. Streams play on every page while the panel is awake (and, if set, while the
 * backlight is off), so looking at the panel never waits for a stream to start. While a stream
 * starts, or when the panel is over its live limit, the tile shows a snapshot instead of black.
 * Tap for full screen.
 */
@Composable
fun CameraTile(tile: Tile) {
    val app = LocalContext.current.panel
    val cameras by app.config.cameras.collectAsState()
    val screen by app.screen.state.collectAsState()
    val camera = tile.prop("camera") ?: return Card { Title("Pick a camera", maxHeight) }

    val stream = cameras.streamUrl(camera)
    val fullStream = tile.prop("fullStream")?.let(cameras::streamUrl) ?: stream
    val snapshot = tile.prop("snapshot") ?: cameras.snapshotUrl(camera)
    val fit = tile.prop("fit") == "contain"
    // A preview shows the snapshot only: no stream, live slot or status of the panel's own tile.
    if (LocalPreview.current) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            Snapshot(snapshot, fit, refreshMs = 0L)
            if (stream == null) Hint("Set the go2rtc address on the website, or use an rtsp:// URL")
            tile.prop("name")?.let { name ->
                Text(
                    name,
                    color = Color.White,
                    fontSize = detail(14.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        return
    }
    val awake = screen == ScreenState.AWAKE || (cameras.keepLiveAsleep && screen == ScreenState.DIMMED)
    val slot = hasLiveSlot(tile.id, cameras.maxLive)
    val live = stream != null && awake && slot
    val label = tile.prop("name") ?: camera
    DisposableEffect(tile.id) { onDispose { CameraStatus.entries.remove(tile.id) } }
    LaunchedEffect(stream, awake, slot) {
        when {
            stream == null -> CameraStatus.set(tile.id, label, null, "No stream: set the go2rtc address, or use an rtsp:// URL")
            !awake -> CameraStatus.set(tile.id, label, stream, "Paused while the panel sleeps")
            !slot -> CameraStatus.set(tile.id, label, stream, "Snapshot only: over the live stream limit")
        }
    }
    var showingVideo by remember(stream) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .clickable(enabled = fullStream != null) { fullScreen = true },
    ) {
        if (live) Video(stream!!, fit, status = { CameraStatus.set(tile.id, label, stream, it) }) { showingVideo = it }
        // Snapshots refresh slowly when the tile isn't live, and stop once the video shows.
        if (!live || !showingVideo) Snapshot(snapshot, fit, refreshMs = if (live) 0L else 10_000L)
        when {
            stream == null -> Hint("Set the go2rtc address on the website, or use an rtsp:// URL")
            tile.prop("name") != null -> Text(
                tile.prop("name")!!,
                color = Color.White,
                fontSize = detail(14.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }

    if (fullScreen && fullStream != null) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var fullShowing by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullScreen = false }) {
                Video(fullStream, fit = true) { fullShowing = it }
                if (!fullShowing) Snapshot(snapshot, fit = true, refreshMs = 0L)
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Color(0xFF9A9A9A), fontSize = 14.sp)
}

// ---- Status ---------------------------------------------------------------------------------

/**
 * What each camera tile is doing, for the website's Cameras tab: connecting, live since when,
 * failing and retrying, snapshot only, or paused. Errors and retries are also logged.
 */
object CameraStatus {
    class Entry(val name: String, val url: String, val text: String)

    val entries = ConcurrentHashMap<String, Entry>()

    fun set(id: String, name: String, url: String?, text: String) {
        entries[id] = Entry(name, url?.let { redact(it) }.orEmpty(), text)
    }

    // Replaces the "user:password" in an rtsp:// address with stars: the website and log never show it.
    fun redact(url: String) = url.replace(Regex("://[^/@]+@"), "://***@")
}


// ---- Live limit ------------------------------------------------------------------------------

// Tiles in the order they asked; the first maxLive stream, the rest show snapshots.
private val liveQueue = mutableStateListOf<String>()

/** Camera tiles on the panel (every page is kept composed); the first maxLive of them play live. */
fun cameraTilesOnPanel(): Int = liveQueue.size

@Composable
private fun hasLiveSlot(id: String, max: Int): Boolean {
    DisposableEffect(id) {
        liveQueue.add(id)
        onDispose { liveQueue.remove(id) }
    }
    return liveQueue.indexOf(id) in 0 until max
}

// ---- Video -----------------------------------------------------------------------------------

/**
 * Plays [url] into a SurfaceView: the hardware decoder hands frames straight to the display,
 * which this tablet's small GPU can't do for several streams. Retries with backoff on errors.
 * [onShowing] says whether a picture is on screen.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun Video(url: String, fit: Boolean, status: (String) -> Unit = {}, onShowing: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) { livePlayer(context, url) }
    var failures by remember(url) { mutableIntStateOf(0) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (failures > 0) EventLog.i("camera", "${CameraStatus.redact(url)}: live again")
                status("Live since ${LocalTime.now().withNano(0)}")
                failures = 0
                onShowing(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                val reason = error.cause?.message ?: error.errorCodeName
                val wait = retryDelay(failures + 1) / 1000
                EventLog.e("camera", "${CameraStatus.redact(url)}: ${error.errorCodeName} ($reason), retrying in $wait s")
                status("Error: $reason. Retrying in $wait s")
                onShowing(false)
                failures++
            }
        }
        player.addListener(listener)
        status("Connecting…")
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
            onShowing(false)
        }
    }
    LaunchedEffect(player, failures) {
        if (failures == 0) return@LaunchedEffect
        delay(retryDelay(failures))
        status("Connecting again…")
        player.prepare()
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = {
            it.resizeMode = if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// 2, 4, 8, 16, 30, 30 … seconds: soon after a blip, without hammering a camera that's gone.
private fun retryDelay(failures: Int) = (1_000L shl failures.coerceIn(1, 5)).coerceAtMost(30_000L)

// A live view, not a film: start on the first frames and keep little buffered.
@OptIn(UnstableApi::class)
private fun livePlayer(context: Context, url: String): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(500, 2_000, 100, 100)
        .build()
    val item = MediaItem.fromUri(url)
    val source = if (url.startsWith("rtsp", ignoreCase = true)) {
        // RTP over TCP: UDP gets lost on Wi-Fi and go2rtc serves TCP fine.
        RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8_000).createMediaSource(item)
    } else {
        DefaultMediaSourceFactory(context).createMediaSource(item)
    }
    return ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
        volume = 0f
        setMediaSource(source)
    }
}

// ---- Snapshots -------------------------------------------------------------------------------

private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

// The last picture of each camera, so a tile shows something the moment it appears.
private val lastSnapshot = ConcurrentHashMap<String, Bitmap>()

@Composable
private fun Snapshot(url: String?, fit: Boolean, refreshMs: Long) {
    if (url == null) return
    var bitmap by remember(url) { mutableStateOf(lastSnapshot[url]) }
    LaunchedEffect(url, refreshMs) {
        while (true) {
            fetchSnapshot(url)?.let {
                lastSnapshot[url] = it
                bitmap = it
            }
            if (refreshMs <= 0L) break
            delay(refreshMs)
        }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Fetches a snapshot into the cache tiles show first (LayoutCompiler); false if it failed. */
internal suspend fun preloadSnapshot(url: String): Boolean =
    fetchSnapshot(url)?.also { lastSnapshot[url] = it } != null

private suspend fun fetchSnapshot(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
