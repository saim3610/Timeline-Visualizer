package com.journeyvisualizer.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.journeyvisualizer.app.MainActivity
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.VideoRepository
import com.journeyvisualizer.app.data.model.Journey
import com.journeyvisualizer.app.history.HistoryRegistration
import com.journeyvisualizer.app.history.HistoryRepository
import com.journeyvisualizer.app.history.ThumbnailStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportRequest(val journey: Journey, val config: ExportConfig)

/**
 * Foreground service that renders the MP4 so export keeps going with the
 * screen off or the app in the background. Progress is shown in a
 * notification with a cancel action.
 */
class ExportService : Service() {

    companion object {
        const val ACTION_START = "com.journeyvisualizer.app.export.START"
        const val ACTION_CANCEL = "com.journeyvisualizer.app.export.CANCEL"
        const val ACTION_START_PHASE7 = "com.journeyvisualizer.app.export.START_PHASE7"
        const val ACTION_CANCEL_PHASE7 = "com.journeyvisualizer.app.export.CANCEL_PHASE7"
        private const val PROGRESS_ID = 1001
        private const val RESULT_ID = 1002
        private const val CHANNEL_ID = "video_export"

        /** Holds the request because a Journey is too large for an Intent. */
        @Volatile
        var pendingRequest: ExportRequest? = null

        /** Holds the Phase 7 request because the timeline is too large for an Intent. */
        @Volatile
        var pendingPhase7Request: Phase7RenderRequest? = null

        fun enqueue(context: Context, journey: Journey, config: ExportConfig) {
            pendingRequest = ExportRequest(journey, config)
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Starts a Phase 7 export. Returns false (and starts nothing) when
         * an export is already running or an identical export is active —
         * the caller should surface the in-flight job instead.
         */
        fun enqueuePhase7(context: Context, request: Phase7RenderRequest): Boolean {
            // Covers the window between the first tap and the service's
            // first bus post: a second tap must not overwrite the pending
            // request or start a second render.
            if (pendingPhase7Request != null || ExportProgressBus.isActive()) return false
            pendingPhase7Request = request
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_START_PHASE7)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun cancelPhase7(context: Context) {
            val intent = Intent(context, ExportService::class.java).setAction(ACTION_CANCEL_PHASE7)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var exportJob: Job? = null

    @Volatile
    private var cancelled = false
    private var lastProgressShown = -1f
    private var lastProgressAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                exportJob?.cancel(CancellationException("User cancelled"))
            }
            ACTION_START_PHASE7 -> startPhase7Export()
            ACTION_CANCEL_PHASE7 -> {
                cancelled = true
                exportJob?.cancel(CancellationException("User cancelled"))
            }
            else -> startExport()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startExport() {
        val req = pendingRequest ?: run { stopSelf(); return }
        pendingRequest = null
        cancelled = false
        lastProgressShown = -1f

        val notif = progressNotification(getString(R.string.notif_preparing), 0f, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                PROGRESS_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(PROGRESS_ID, notif)
        }

        exportJob = scope.launch {
            var uri: Uri? = null
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                uri = VideoRepository.createPendingVideo(
                    this@ExportService, "Journey_$stamp.mp4"
                ) ?: throw IllegalStateException("Could not create the output file")
                VideoExporter(this@ExportService).export(
                    req.journey,
                    req.config,
                    uri,
                    onProgress = { f -> reportProgress(f) },
                    isCancelled = { cancelled }
                )
                VideoRepository.finishPendingVideo(this@ExportService, uri)
                resultNotification(
                    getString(R.string.notif_saved),
                    getString(R.string.notif_ready),
                    uri
                )
            } catch (_: CancellationException) {
                uri?.let { VideoRepository.abortPendingVideo(this@ExportService, it) }
                resultNotification(
                    getString(R.string.notif_cancelled),
                    getString(R.string.notif_cancelled_text),
                    null
                )
            } catch (e: Exception) {
                uri?.let { VideoRepository.abortPendingVideo(this@ExportService, it) }
                resultNotification(
                    getString(R.string.notif_failed),
                    e.message ?: getString(R.string.notif_unknown),
                    null
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 7 export — deterministic timeline + composition render.
    // ------------------------------------------------------------------

    private fun startPhase7Export() {
        val req = pendingPhase7Request ?: run { stopSelf(); return }
        pendingPhase7Request = null
        // Never run two renders at once; the UI also guards via the bus.
        if (exportJob?.isActive == true) return
        cancelled = false
        lastProgressShown = -1f

        ExportProgressBus.reset()
        var currentState: RenderState = RenderState.Preparing
        ExportProgressBus.post(currentState)

        val notif = progressNotification(getString(R.string.notif_preparing), 0f, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                PROGRESS_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(PROGRESS_ID, notif)
        }

        exportJob = scope.launch {
            var uri: Uri? = null
            var fileName = ""
            try {
                val base = ExportFileNamer.baseName(System.currentTimeMillis())
                fileName = ExportFileNamer.uniqueName(base) { name ->
                    VideoRepository.displayNameExists(this@ExportService, name)
                }
                uri = VideoRepository.createPendingVideo(this@ExportService, fileName)
                    ?: throw RenderException(RenderError.StorageFailure)
                val outUri = uri
                VideoExporter(this@ExportService).exportPhase7(
                    req,
                    outUri,
                    onState = { state ->
                        currentState = state
                        ExportProgressBus.post(state)
                    },
                    onProgress = { progress ->
                        ExportProgressBus.post(currentState, progress)
                        reportPhase7Progress(progress)
                    },
                    isCancelled = { cancelled },
                )
                // Finalize the MediaStore entry, then verify the output
                // before anything is reported as complete.
                VideoRepository.finishPendingVideo(this@ExportService, outUri)
                val info = OutputValidator.validate(
                    this@ExportService, outUri,
                    req.spec.totalVideoMs, req.spec.exportWidth, req.spec.exportHeight,
                ).getOrElse { throw it }
                // Phase 8: register the finalized video in local history.
                // Registration is best-effort — a DB failure must not turn a
                // good export into a failure; the video itself is already
                // finalized and playable.
                val historyId = registerInHistory(req, info, outUri, fileName)
                val completed = RenderState.Completed(
                    uri = outUri,
                    fileName = fileName,
                    durationMs = info.durationMs,
                    width = info.width,
                    height = info.height,
                    fps = req.spec.fps,
                    sizeBytes = info.sizeBytes,
                    historyId = historyId,
                )
                ExportProgressBus.post(completed)
                resultNotification(
                    getString(R.string.notif_saved),
                    getString(R.string.notif_video_ready, fileName),
                    outUri,
                )
            } catch (_: CancellationException) {
                uri?.let { VideoRepository.abortPendingVideo(this@ExportService, it) }
                ExportProgressBus.post(RenderState.Cancelled)
                resultNotification(
                    getString(R.string.notif_cancelled),
                    getString(R.string.notif_cancelled_text),
                    null,
                )
            } catch (e: Exception) {
                uri?.let { VideoRepository.abortPendingVideo(this@ExportService, it) }
                val error = mapToRenderError(e)
                ExportProgressBus.post(RenderState.Failed(error.userMessage))
                resultNotification(
                    getString(R.string.notif_failed),
                    error.userMessage,
                    null,
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Phase 8: inserts the finalized export into the local video history
     * and generates its cached thumbnail. Only called after the MP4 has
     * been verified — failed/cancelled renders never create records.
     * Returns the history id, or null when registration failed.
     */
    private suspend fun registerInHistory(
        req: Phase7RenderRequest,
        info: OutputValidator.Info,
        outUri: Uri,
        fileName: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val repo = HistoryRepository(this@ExportService)
            val item = HistoryRegistration.build(req, info, outUri, fileName)
            repo.insert(item)
            val thumbPath = ThumbnailStore.generate(this@ExportService, outUri, item.id)
            if (thumbPath != null) repo.setThumbnailPath(item.id, thumbPath)
            item.id
        } catch (_: Exception) {
            null
        }
    }

    private fun reportPhase7Progress(p: RenderProgress) {
        val f = p.fraction
        val now = System.currentTimeMillis()
        if (f - lastProgressShown < 0.02f && now - lastProgressAt < 800) return
        lastProgressShown = f
        lastProgressAt = now
        val pct = (f * 100).toInt().coerceIn(0, 100)
        val text = if (p.framesPerSecond > 0) {
            getString(R.string.notif_rendering_fps, pct, p.framesPerSecond.toInt())
        } else {
            getString(R.string.notif_rendering, pct)
        }
        getSystemService(NotificationManager::class.java)
            .notify(PROGRESS_ID, progressNotification(text, f, indeterminate = false))
    }

    private fun reportProgress(f: Float) {
        val now = System.currentTimeMillis()
        if (f - lastProgressShown < 0.02f && now - lastProgressAt < 800) return
        lastProgressShown = f
        lastProgressAt = now
        val pct = (f * 100).toInt().coerceIn(0, 100)
        getSystemService(NotificationManager::class.java)
            .notify(PROGRESS_ID, progressNotification(getString(R.string.notif_rendering, pct), f, false))
    }

    private fun progressNotification(text: String, f: Float, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 0,
            Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(getString(R.string.notif_creating))
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, (f * 100).toInt().coerceIn(0, 100), indeterminate)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notif_cancel), cancel).build()
            )
            .build()
    }

    private fun resultNotification(title: String, text: String, videoUri: Uri?) {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("tab", "videos"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, notif)
    }
}
