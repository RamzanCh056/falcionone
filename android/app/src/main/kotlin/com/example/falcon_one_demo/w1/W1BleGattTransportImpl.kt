package com.example.falcon_one_demo.w1

/**
 * [W1BleTransport] that tears down GATT before Wi-Fi (serialized by [W1TransferEngine]).
 */
class W1BleGattTransportImpl(
    private val coordinator: W1BleGattCoordinator,
) : W1BleTransport {
    override suspend fun acknowledgeRecordingComplete(sessionId: String, recordingId: String) {
        // Optional vendor ACK write — implement when W1 protocol specifies it.
    }

    override suspend fun releaseForWifiHandoff(sessionId: String) {
        coordinator.disconnect()
    }
}
