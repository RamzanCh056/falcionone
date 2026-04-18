package com.example.falcon_one_demo.w1

/** Internal engine phases (finer than [W1PipelineState]). */
enum class W1Phase {
    Idle,
    BluetoothConnected,
    RecordingCompleteReceived,
    BleHandoff,
    WifiConnected,
    ResolvingLatestFile,
    Downloading,
    Validating,
    Persisting,
    Done,
    Failed,
}

data class W1UiState(
    val phase: W1Phase,
    val detail: String,
    val progress: Float,
    val sessionId: String,
    val recordingId: String?,
    val localPath: String?,
    val error: String?,
) {
    val pipelineState: W1PipelineState get() = W1StateMapper.fromEnginePhase(phase)

    fun toMap(): Map<String, Any?> = mapOf(
        "phase" to phase.name,
        "pipelineState" to pipelineState.name,
        "detail" to detail,
        "progress" to progress.toDouble(),
        "sessionId" to sessionId,
        "recordingId" to recordingId,
        "localPath" to localPath,
        "error" to error,
    )
}

data class RemoteRecording(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val sha256Hex: String?,
    val completedAtEpochMs: Long,
)
