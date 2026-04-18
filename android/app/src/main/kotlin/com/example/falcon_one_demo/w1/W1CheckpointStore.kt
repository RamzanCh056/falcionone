package com.example.falcon_one_demo.w1

import android.content.Context

/**
 * Persists handoff checkpoints so a crash mid Wi-Fi does not lose which recording to resume.
 */
interface W1CheckpointStore {
    fun savePending(recordingId: String, extrasJson: String?)
    fun clearPending()
    fun readPending(): Pair<String?, String?>

    /** Last successfully persisted recording id (for idempotency). */
    fun lastCompletedRecordingId(): String?
    fun setLastCompletedRecordingId(id: String)
}

class SharedPrefsW1CheckpointStore(context: Context) : W1CheckpointStore {
    private val prefs = context.getSharedPreferences("w1_checkpoints", Context.MODE_PRIVATE)

    override fun savePending(recordingId: String, extrasJson: String?) {
        prefs.edit()
            .putString("pending_id", recordingId)
            .putString("pending_extras", extrasJson)
            .apply()
    }

    override fun clearPending() {
        prefs.edit().remove("pending_id").remove("pending_extras").apply()
    }

    override fun readPending(): Pair<String?, String?> =
        prefs.getString("pending_id", null) to prefs.getString("pending_extras", null)

    override fun lastCompletedRecordingId(): String? = prefs.getString("last_completed_id", null)

    override fun setLastCompletedRecordingId(id: String) {
        prefs.edit().putString("last_completed_id", id).apply()
    }
}

/** For JVM unit tests without Android Context. */
class InMemoryW1CheckpointStore : W1CheckpointStore {
    var pending: Pair<String?, String?> = null to null
    var lastCompleted: String? = null

    override fun savePending(recordingId: String, extrasJson: String?) {
        pending = recordingId to extrasJson
    }

    override fun clearPending() {
        pending = null to null
    }

    override fun readPending(): Pair<String?, String?> = pending

    override fun lastCompletedRecordingId(): String? = lastCompleted

    override fun setLastCompletedRecordingId(id: String) {
        lastCompleted = id
    }
}
