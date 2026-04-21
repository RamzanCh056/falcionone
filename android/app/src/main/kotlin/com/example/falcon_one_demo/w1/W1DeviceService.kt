package com.example.falcon_one_demo.w1

import android.content.Context

/**
 * High-level device façade: BLE discovery + handoff hooks around [W1TransferEngine].
 *
 * **Layers:** Flutter UI → MethodChannel → [W1DeviceService] → BLE / Wi‑Fi / [W1TransferEngine].
 */
interface W1DeviceService {
    fun start()
    fun stop()
}

/** No-op BLE; use Flutter "simulate" + mock HTTP for CI / TeamViewer. */
class W1MockDeviceService : W1DeviceService {
    override fun start() {}
    override fun stop() {}
}

/**
 * Starts BLE scan/connect/notify and wires Wi‑Fi bind + OkHttp to the active [android.net.Network].
 * Requires runtime Bluetooth permissions on API 31+.
 */
class RealW1DeviceService(
    private val appContext: Context,
    private val engine: W1TransferEngine,
    private val switchableBle: W1SwitchableBleTransport,
    private val logger: W1Logger,
    private val uuids: W1DeviceUuids,
    private val wifiBinder: W1WifiNetworkBinder,
) : W1DeviceService {

    private var coordinator: W1BleGattCoordinator? = null

    private fun installEngineHooks() {
        engine.beforeWifiHttp = { sessionId ->
            val net = wifiBinder.awaitWifiAndBind(sessionId)
            engine.setWifiClient(
                OkHttpW1WifiFileClient(W1OkHttpFactories.clientForNetwork(net)),
            )
        }
        engine.afterPipeline = { sessionId ->
            wifiBinder.unbind(sessionId)
            engine.setWifiClient(OkHttpW1WifiFileClient())
        }
    }

    private fun newCoordinator(): W1BleGattCoordinator {
        return W1BleGattCoordinator(
            context = appContext,
            logger = logger,
            uuids = uuids,
            sessionIdForLogs = { engine.currentState().sessionId.ifEmpty { "ble" } },
            onPipeline = { ps, detail ->
                when (ps) {
                    W1PipelineState.BLUETOOTH_CONNECTED ->
                        engine.postConnectionUiPhase(W1Phase.BluetoothConnected, detail)
                    W1PipelineState.IDLE -> {
                        if (!engine.isTransferActive()) {
                            engine.postConnectionUiPhase(W1Phase.Idle, detail)
                        }
                    }
                    else -> {}
                }
            },
            onRecordingCompleteJson = { id, extras ->
                engine.onBleRecordingComplete(id, extras, forceRedownload = false)
            },
            onBleError = { msg ->
                logger.e(
                    engine.currentState().sessionId.ifEmpty { "ble" },
                    "ble_error",
                    mapOf("message" to msg),
                    IllegalStateException(msg),
                )
            },
        )
    }

    override fun start() {
        stop()
        installEngineHooks()
        val c = newCoordinator()
        coordinator = c
        switchableBle.delegate = W1BleGattTransportImpl(c)
        c.startScanIfPermitted()
    }

    /**
     * Direct GATT connect for exploration (no scan UUID filters).
     * Safe if [start] was never called: installs engine hooks and a coordinator without starting a scan.
     */
    fun connectToDevice(macAddress: String) {
        installEngineHooks()
        if (coordinator == null) {
            val c = newCoordinator()
            coordinator = c
            switchableBle.delegate = W1BleGattTransportImpl(c)
        }
        coordinator?.connectToDevice(macAddress.trim())
            ?: logger.e(
                engine.currentState().sessionId.ifEmpty { "ble" },
                "ble_connect_skipped",
                mapOf("reason" to "coordinator_null"),
                null,
            )
    }

    /** Stop scan → short delay → single GATT open with [BluetoothDevice.TRANSPORT_LE] (debug / conflict mitigation). */
    fun forceBleSafeConnect(macAddress: String) {
        installEngineHooks()
        if (coordinator == null) {
            val c = newCoordinator()
            coordinator = c
            switchableBle.delegate = W1BleGattTransportImpl(c)
        }
        coordinator?.forceBleSafeConnect(macAddress.trim())
            ?: logger.e(
                engine.currentState().sessionId.ifEmpty { "ble" },
                "ble_force_safe_skipped",
                mapOf("reason" to "coordinator_null"),
                null,
            )
    }

    /** Try GATT connect to strongest unnamed peripherals (RSSI > -70), up to 3 attempts. */
    fun runAnonymousConnectProbe() {
        if (coordinator == null) {
            installEngineHooks()
            val c = newCoordinator()
            coordinator = c
            switchableBle.delegate = W1BleGattTransportImpl(c)
        }
        coordinator?.runAnonymousConnectProbe()
            ?: logger.e(
                engine.currentState().sessionId.ifEmpty { "ble" },
                "ble_probe_skipped",
                mapOf("reason" to "coordinator_null"),
                null,
            )
    }

    override fun stop() {
        coordinator?.disconnect()
        coordinator = null
        switchableBle.delegate = W1BleStubTransport(logger)
        engine.beforeWifiHttp = {}
        engine.afterPipeline = {}
    }
}
