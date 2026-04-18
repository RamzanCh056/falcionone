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

    override fun start() {
        stop()
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

        val c = W1BleGattCoordinator(
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
        coordinator = c
        switchableBle.delegate = W1BleGattTransportImpl(c)
        c.startScanIfPermitted()
    }

    override fun stop() {
        coordinator?.disconnect()
        coordinator = null
        switchableBle.delegate = W1BleStubTransport(logger)
        engine.beforeWifiHttp = {}
        engine.afterPipeline = {}
    }
}
