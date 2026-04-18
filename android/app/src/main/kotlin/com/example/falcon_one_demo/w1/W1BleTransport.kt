package com.example.falcon_one_demo.w1

import kotlinx.coroutines.delay

/**
 * BLE side of the W1 handoff. Real implementation would own GATT, UUIDs, and vendor commands.
 */
interface W1BleTransport {
    /** Optional ACK to device after parsing "recording complete". */
    suspend fun acknowledgeRecordingComplete(sessionId: String, recordingId: String)

    /** Release radio / disconnect GATT before Wi-Fi work (serialized by engine). */
    suspend fun releaseForWifiHandoff(sessionId: String)
}

/** Safe default for bring-up: no hardware. */
class W1BleNoOpTransport : W1BleTransport {
    override suspend fun acknowledgeRecordingComplete(sessionId: String, recordingId: String) {}

    override suspend fun releaseForWifiHandoff(sessionId: String) {}
}

/**
 * Simulates vendor latency; swap for [W1BleGattTransport] when UUIDs are known.
 */
/** Delegates to a swappable implementation (stub vs GATT) without recreating [W1TransferEngine]. */
class W1SwitchableBleTransport(
    initial: W1BleTransport,
) : W1BleTransport {
    @Volatile
    var delegate: W1BleTransport = initial

    override suspend fun acknowledgeRecordingComplete(sessionId: String, recordingId: String) {
        delegate.acknowledgeRecordingComplete(sessionId, recordingId)
    }

    override suspend fun releaseForWifiHandoff(sessionId: String) {
        delegate.releaseForWifiHandoff(sessionId)
    }
}

class W1BleStubTransport(
    private val logger: W1Logger,
    private val ackDelayMs: Long = 50L,
    private val releaseDelayMs: Long = 80L,
) : W1BleTransport {
    override suspend fun acknowledgeRecordingComplete(sessionId: String, recordingId: String) {
        logger.i(sessionId, "ble_stub_ack", mapOf("recordingId" to recordingId))
        delay(ackDelayMs)
    }

    override suspend fun releaseForWifiHandoff(sessionId: String) {
        logger.i(sessionId, "ble_stub_release", emptyMap())
        delay(releaseDelayMs)
    }
}
