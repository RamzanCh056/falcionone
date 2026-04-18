package com.example.falcon_one_demo.w1

/**
 * User-visible pipeline states (single writer: [W1TransferEngine] + connection overlay).
 * Maps product language to Logcat / Flutter.
 */
enum class W1PipelineState {
    IDLE,
    BLUETOOTH_CONNECTED,
    RECORDING_DETECTED,
    SWITCHING_TO_WIFI,
    WIFI_CONNECTED,
    FETCHING_FILE,
    DOWNLOADING,
    SUCCESS,
    ERROR,
}
