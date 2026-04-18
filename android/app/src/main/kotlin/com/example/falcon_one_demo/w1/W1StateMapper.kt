package com.example.falcon_one_demo.w1

/** Maps internal engine phases to [W1PipelineState] for UI and remote debugging. */
object W1StateMapper {
    fun fromEnginePhase(phase: W1Phase): W1PipelineState = when (phase) {
        W1Phase.Idle -> W1PipelineState.IDLE
        W1Phase.BluetoothConnected -> W1PipelineState.BLUETOOTH_CONNECTED
        W1Phase.RecordingCompleteReceived -> W1PipelineState.RECORDING_DETECTED
        W1Phase.BleHandoff -> W1PipelineState.SWITCHING_TO_WIFI
        W1Phase.WifiConnected -> W1PipelineState.WIFI_CONNECTED
        W1Phase.ResolvingLatestFile -> W1PipelineState.FETCHING_FILE
        W1Phase.Downloading, W1Phase.Validating, W1Phase.Persisting -> W1PipelineState.DOWNLOADING
        W1Phase.Done -> W1PipelineState.SUCCESS
        W1Phase.Failed -> W1PipelineState.ERROR
    }
}
