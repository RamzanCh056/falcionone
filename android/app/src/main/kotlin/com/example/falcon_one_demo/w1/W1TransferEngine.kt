package com.example.falcon_one_demo.w1

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class W1TransferEngine(
    private val appScope: CoroutineScope,
    private val mainHandler: Handler,
    private val workspace: File,
    private val logger: W1Logger,
    private val checkpoints: W1CheckpointStore,
    private val ble: W1BleTransport,
    private var wifi: W1WifiFileClient,
    private val onUiState: (W1UiState) -> Unit,
) {
    private val mutex = Mutex()
    private val stateRef = AtomicReference(
        W1UiState(
            phase = W1Phase.Idle,
            detail = "",
            progress = 0f,
            sessionId = "",
            recordingId = null,
            localPath = null,
            error = null,
        ),
    )

    @Volatile
    var wifiBaseUrl: String = "http://10.0.2.2:8765"
        private set

    /**
     * Invoked after BLE release, before first HTTP list call — bind process to recorder Wi‑Fi,
     * swap [OkHttpClient] for a [android.net.Network], etc.
     */
    var beforeWifiHttp: suspend (sessionId: String) -> Unit = { }

    /** Always invoked when the transfer attempt finishes (success, failure, or early exit). */
    var afterPipeline: suspend (sessionId: String) -> Unit = { }

    fun configureWifiBaseUrl(url: String) {
        wifiBaseUrl = url.trimEnd('/')
    }

    fun setWifiClient(client: W1WifiFileClient) {
        wifi = client
    }

    fun currentState(): W1UiState = stateRef.get()

    fun isTransferActive(): Boolean = isPipelineBusy(stateRef.get().phase)

    private fun push(state: W1UiState) {
        stateRef.set(state)
        mainHandler.post { onUiState(state) }
    }

    /**
     * Connection-only UI updates from [W1BleGattCoordinator] (does not take [mutex] — never call
     * from inside [runPipelineLocked]).
     */
    fun postConnectionUiPhase(phase: W1Phase, detail: String) {
        val cur = stateRef.get()
        val st = W1UiState(
            phase = phase,
            detail = detail,
            progress = cur.progress,
            sessionId = cur.sessionId.ifEmpty { "—" },
            recordingId = cur.recordingId,
            localPath = cur.localPath,
            error = cur.error,
        )
        mainHandler.post {
            stateRef.set(st)
            onUiState(st)
        }
    }

    private fun isPipelineBusy(phase: W1Phase): Boolean {
        val o = phase.ordinal
        return o in W1Phase.RecordingCompleteReceived.ordinal..W1Phase.Persisting.ordinal
    }

    /**
     * Entry from BLE stack when recording completes. Serialized; duplicates ignored while busy.
     */
    fun onBleRecordingComplete(
        recordingId: String,
        extras: Map<String, String?> = emptyMap(),
        forceRedownload: Boolean = false,
    ) {
        appScope.launch {
            mutex.withLock {
                val phase = stateRef.get().phase
                if (isPipelineBusy(phase)) {
                    logger.w(
                        newSessionPlaceholder(),
                        "recording_complete_ignored_busy",
                        mapOf("currentPhase" to phase.name, "recordingId" to recordingId),
                    )
                    return@withLock
                }
                runPipelineLocked(recordingId, extras, forceRedownload)
            }
        }
    }

    /** Resume after process death if [W1CheckpointStore] still has a pending id. */
    fun resumePendingIfNeeded() {
        appScope.launch {
            mutex.withLock {
                val phase = stateRef.get().phase
                if (isPipelineBusy(phase)) return@withLock
                val (id, _) = checkpoints.readPending()
                if (id.isNullOrBlank()) return@withLock
                runPipelineLocked(id, emptyMap(), forceRedownload = true)
            }
        }
    }

    private fun newSessionPlaceholder() = "no-session"

    private suspend fun runPipelineLocked(
        recordingId: String,
        extras: Map<String, String?>,
        forceRedownload: Boolean,
    ) {
        val sessionId = UUID.randomUUID().toString()
        if (!forceRedownload && checkpoints.lastCompletedRecordingId() == recordingId) {
            logger.i(sessionId, "skip_already_completed", mapOf("recordingId" to recordingId))
            push(
                W1UiState(
                    phase = W1Phase.Done,
                    detail = "Already downloaded",
                    progress = 1f,
                    sessionId = sessionId,
                    recordingId = recordingId,
                    localPath = null,
                    error = null,
                ),
            )
            return
        }

        val extrasJson = JSONObject().apply {
            for ((k, v) in extras) {
                if (v != null) put(k, v)
            }
        }.toString()
        checkpoints.savePending(recordingId, extrasJson)
        logger.i(
            sessionId,
            "pipeline_start",
            mapOf("recordingId" to recordingId, "wifiBaseUrl" to wifiBaseUrl),
        )

        fun step(phase: W1Phase, detail: String, p: Float) {
            push(
                W1UiState(phase, detail, p, sessionId, recordingId, null, null),
            )
        }

        try {
            step(W1Phase.RecordingCompleteReceived, "BLE event persisted", 0.05f)

            ble.acknowledgeRecordingComplete(sessionId, recordingId)
            step(W1Phase.BleHandoff, "BLE ACK done", 0.12f)

            ble.releaseForWifiHandoff(sessionId)
            beforeWifiHttp(sessionId)
            step(W1Phase.WifiConnected, "Wi-Fi path ready for HTTP", 0.22f)

            val listResult = withRetries(sessionId, "list_recordings", logger) {
                wifi.listRecordings(wifiBaseUrl)
            }.getOrElse { throw it }

            step(W1Phase.ResolvingLatestFile, "Listed ${listResult.size} remote files", 0.28f)

            val chosen = pickLatest(listResult, recordingId)
                ?: throw IllegalStateException("No completed recording on device")

            logger.i(
                sessionId,
                "picked_recording",
                mapOf(
                    "id" to chosen.id,
                    "sizeBytes" to chosen.sizeBytes,
                    "name" to chosen.name,
                ),
            )

            val recordingsDir = File(workspace, "recordings").apply { mkdirs() }
            val temp = File(recordingsDir, "${chosen.id}.download.part")
            val safeName =
                chosen.name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .take(64)
                    .ifBlank { "recording.bin" }
            val finalFile = File(recordingsDir, "${chosen.id}_$safeName")

            step(W1Phase.Downloading, "Downloading ${chosen.name}", 0.35f)
            withRetries(sessionId, "download", logger, maxAttempts = 4) {
                wifi.downloadRecording(
                    wifiBaseUrl,
                    chosen.id,
                    temp,
                    chosen.sizeBytes,
                ) { loaded, total ->
                    val frac = if (total > 0) (loaded.toDouble() / total.toDouble()).toFloat() else 0f
                    push(
                        W1UiState(
                            W1Phase.Downloading,
                            "$loaded / $total bytes",
                            0.35f + 0.45f * frac.coerceIn(0f, 1f),
                            sessionId,
                            recordingId,
                            null,
                            null,
                        ),
                    )
                }
            }.getOrElse { throw it }

            step(W1Phase.Validating, "Validating size/checksum", 0.82f)
            validateFile(temp, chosen)

            step(W1Phase.Persisting, "Committing file", 0.92f)
            if (finalFile.exists()) finalFile.delete()
            if (!temp.renameTo(finalFile)) {
                temp.copyTo(finalFile, overwrite = true)
                temp.delete()
            }

            checkpoints.setLastCompletedRecordingId(chosen.id)
            checkpoints.clearPending()

            logger.i(
                sessionId,
                "pipeline_done",
                mapOf("path" to finalFile.absolutePath, "bytes" to finalFile.length()),
            )

            push(
                W1UiState(
                    W1Phase.Done,
                    "Saved",
                    1f,
                    sessionId,
                    chosen.id,
                    finalFile.absolutePath,
                    null,
                ),
            )
        } catch (t: Throwable) {
            logger.e(sessionId, "pipeline_failed", mapOf("recordingId" to recordingId), t)
            push(
                W1UiState(
                    W1Phase.Failed,
                    t.message ?: t.javaClass.simpleName,
                    0f,
                    sessionId,
                    recordingId,
                    null,
                    t.message,
                ),
            )
        } finally {
            try {
                afterPipeline(sessionId)
            } catch (ignored: Exception) {
                logger.w(sessionId, "after_pipeline_failed", mapOf("error" to (ignored.message ?: "")))
            }
        }
    }

    private fun pickLatest(list: List<RemoteRecording>, bleHintId: String): RemoteRecording? {
        if (list.isEmpty()) return null
        val byHint = list.firstOrNull { it.id == bleHintId }
        if (byHint != null) return byHint
        return list.maxByOrNull { it.completedAtEpochMs }
    }

    private fun validateFile(file: File, meta: RemoteRecording) {
        val len = file.length()
        if (len != meta.sizeBytes) {
            throw IllegalStateException("size mismatch expected=${meta.sizeBytes} actual=$len")
        }
        val expectedSha = meta.sha256Hex?.lowercase()
        if (!expectedSha.isNullOrBlank()) {
            val actual = sha256Hex(file.readBytes())
            if (actual != expectedSha) {
                throw IllegalStateException("sha256 mismatch")
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /** Clears on-screen phase back to [W1Phase.Idle] when not running a pipeline. */
    fun resetDisplayIfIdle() {
        appScope.launch {
            mutex.withLock {
                val p = stateRef.get().phase
                if (!isPipelineBusy(p)) {
                    push(
                        W1UiState(
                            W1Phase.Idle,
                            "",
                            0f,
                            "",
                            null,
                            null,
                            null,
                        ),
                    )
                }
            }
        }
    }
}
