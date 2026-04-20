package com.example.falcon_one_demo.w1

/**
 * W1 integration — architecture (in-code).
 *
 * **Layers**
 * - Flutter: debug UI + MethodChannel / EventChannel — no BLE/Wi‑Fi APIs.
 * - Bridge: `W1FlutterBridge` — wires channels, chooses mock vs real.
 * - Device façade: `W1DeviceService` — `W1MockDeviceService` (CI) vs `RealW1DeviceService` (BLE + bind).
 * - Transfer core: `W1TransferEngine` — single Mutex for the download pipeline;
 *   connection-only UI via `postConnectionUiPhase` (never inside mutex).
 * - Transports: `W1BleTransport` + `W1SwitchableBleTransport`, `W1WifiFileClient` + `OkHttpW1WifiFileClient`.
 * - Infrastructure: `W1Logger` (JSON + Logcat tag W1), `W1CheckpointStore`, `W1WifiNetworkBinder`.
 *
 * **State machine (user-facing)** — `W1PipelineState` derived from `W1Phase` via `W1StateMapper`:
 * IDLE, BLUETOOTH_CONNECTED, RECORDING_DETECTED, SWITCHING_TO_WIFI, WIFI_CONNECTED,
 * FETCHING_FILE, DOWNLOADING, SUCCESS, ERROR.
 *
 * **Race avoidance**
 * - One mutex serializes `onBleRecordingComplete` / resume / `runPipelineLocked`.
 * - GATT callbacks must not call `runPipelineLocked`; they call `onBleRecordingComplete` or `postConnectionUiPhase`.
 * - Wi‑Fi bind/unbind paired in `beforeWifiHttp` / `afterPipeline`.
 * - Retries: `withRetries` with exponential backoff + jitter.
 *
 * **Log samples (Logcat tag W1)**
 * - `{"event":"ble_scan_start","mode":"unfiltered_all_devices"}`
 * - `{"event":"ble_device_seen","name":"...","address":"...","rssi":-42}`
 * - `{"event":"wifi_bind","network":"100","bindOk":true}`
 * - `{"event":"picked_recording","id":"rec-1","sizeBytes":123456}`
 * - `{"event":"retry","phase":"download","attempt":2,"maxAttempts":4}`
 *
 * **Replace for production**: `W1DeviceUuids` and JSON in `W1BleGattCoordinator` per vendor spec;
 * narrow `W1WifiNetworkBinder` to the recorder SSID when known.
 */
internal object W1ArchitectureDoc
