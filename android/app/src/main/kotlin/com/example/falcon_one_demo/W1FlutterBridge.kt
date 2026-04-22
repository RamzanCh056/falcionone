package com.example.falcon_one_demo

import android.os.Handler
import android.os.Looper
import com.example.falcon_one_demo.w1.MockW1WifiFileClient
import com.example.falcon_one_demo.w1.OkHttpW1WifiFileClient
import com.example.falcon_one_demo.w1.RealW1DeviceService
import com.example.falcon_one_demo.w1.RemoteRecording
import com.example.falcon_one_demo.w1.SharedPrefsW1CheckpointStore
import com.example.falcon_one_demo.w1.W1BleStubTransport
import com.example.falcon_one_demo.w1.W1DeviceUuids
import com.example.falcon_one_demo.w1.W1Logger
import com.example.falcon_one_demo.w1.W1SwitchableBleTransport
import com.example.falcon_one_demo.w1.W1TransferEngine
import com.example.falcon_one_demo.w1.W1WifiNetworkBinder
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * Wires [W1TransferEngine] to Flutter via [MethodChannel] + [EventChannel].
 */
object W1FlutterBridge {
    const val methodChannelName = "com.example.falcon_one_demo/w1"
    const val eventChannelName = "com.example.falcon_one_demo/w1_state"
    const val bleScanEventChannelName = "com.example.falcon_one_demo/w1_ble_scan"

    fun attach(activity: FlutterActivity, engine: FlutterEngine) {
        val ctx = activity.applicationContext
        val workspace = ctx.filesDir.resolve("w1_workspace")
        val logDir = ctx.filesDir.resolve("w1_logs")
        workspace.mkdirs()
        logDir.mkdirs()

        val logger = W1Logger(logDir = logDir)
        val checkpoints = SharedPrefsW1CheckpointStore(ctx)
        val switchableBle = W1SwitchableBleTransport(W1BleStubTransport(logger))
        val wifiClient = OkHttpW1WifiFileClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mainHandler = Handler(Looper.getMainLooper())
        val eventSinkRef = AtomicReference<EventChannel.EventSink?>(null)
        val bleScanSinkRef = AtomicReference<EventChannel.EventSink?>(null)

        val transferEngine = W1TransferEngine(
            appScope = scope,
            mainHandler = mainHandler,
            workspace = workspace,
            logger = logger,
            checkpoints = checkpoints,
            ble = switchableBle,
            wifi = wifiClient,
            onUiState = { st ->
                eventSinkRef.get()?.success(st.toMap())
            },
        )

        val wifiBinder = W1WifiNetworkBinder(ctx, logger)
        val realW1Device = RealW1DeviceService(
            appContext = ctx,
            engine = transferEngine,
            switchableBle = switchableBle,
            logger = logger,
            uuids = W1DeviceUuids(),
            wifiBinder = wifiBinder,
            onBleScanUi = { payload ->
                mainHandler.post {
                    bleScanSinkRef.get()?.success(payload)
                }
            },
        )

        EventChannel(engine.dartExecutor.binaryMessenger, eventChannelName).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSinkRef.set(events)
                    events?.success(transferEngine.currentState().toMap())
                }

                override fun onCancel(arguments: Any?) {
                    eventSinkRef.set(null)
                }
            },
        )

        EventChannel(engine.dartExecutor.binaryMessenger, bleScanEventChannelName).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    bleScanSinkRef.set(events)
                }

                override fun onCancel(arguments: Any?) {
                    bleScanSinkRef.set(null)
                }
            },
        )

        MethodChannel(engine.dartExecutor.binaryMessenger, methodChannelName).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "configure" -> {
                        val url = call.argument<String>("wifiBaseUrl")
                        if (!url.isNullOrBlank()) {
                            transferEngine.configureWifiBaseUrl(url)
                        }
                        result.success(null)
                    }

                    "simulateRecordingComplete" -> {
                        val id = call.argument<String>("recordingId")
                            ?: error("recordingId is required")
                        val force = call.argument<Boolean>("force") == true
                        transferEngine.onBleRecordingComplete(id, emptyMap(), force)
                        result.success(null)
                    }

                    "useBuiltInMockWifi" -> {
                        val enabled = call.argument<Boolean>("enabled") == true
                        if (enabled) {
                            val bytes = "hello-w1-mock".toByteArray(Charsets.UTF_8)
                            val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { b -> "%02x".format(b) }
                            val mock = MockW1WifiFileClient(
                                recordings = listOf(
                                    RemoteRecording(
                                        id = "rec-mock-1",
                                        name = "demo.bin",
                                        sizeBytes = bytes.size.toLong(),
                                        sha256Hex = hex,
                                        completedAtEpochMs = System.currentTimeMillis(),
                                    ),
                                ),
                                contentById = mapOf("rec-mock-1" to bytes),
                            )
                            transferEngine.setWifiClient(mock)
                            transferEngine.configureWifiBaseUrl("mock://device")
                        } else {
                            transferEngine.setWifiClient(OkHttpW1WifiFileClient())
                            val url = call.argument<String>("wifiBaseUrl")
                            transferEngine.configureWifiBaseUrl(
                                if (url.isNullOrBlank()) "http://10.0.2.2:8765" else url,
                            )
                        }
                        result.success(null)
                    }

                    "resumePending" -> {
                        transferEngine.resumePendingIfNeeded()
                        result.success(null)
                    }

                    "resetDisplay" -> {
                        transferEngine.resetDisplayIfIdle()
                        result.success(null)
                    }

                    "getRecentLogs" -> {
                        val limit = call.argument<Int>("limit") ?: 200
                        result.success(logger.recentLines(limit))
                    }

                    "exportLogs" -> {
                        val out = ctx.cacheDir.resolve("w1_logs_export.txt")
                        val ok = logger.exportRingToFile(out)
                        result.success(if (ok) out.absolutePath else null)
                    }

                    "connectW1Ble" -> {
                        val mac = call.argument<String>("macAddress")?.trim().orEmpty()
                        if (mac.isEmpty()) {
                            result.error("W1_ERROR", "macAddress is required", null)
                        } else {
                            val localNameHint = call.argument<String>("localName")?.trim()
                            realW1Device.connectToDevice(mac, localNameHint)
                            result.success(null)
                        }
                    }

                    "forceBleSafeConnect" -> {
                        val mac = call.argument<String>("macAddress")?.trim().orEmpty()
                        if (mac.isEmpty()) {
                            result.error("W1_ERROR", "macAddress is required", null)
                        } else {
                            val localNameHint = call.argument<String>("localName")?.trim()
                            realW1Device.forceBleSafeConnect(mac, localNameHint)
                            result.success(null)
                        }
                    }

                    else -> result.notImplemented()
                }
            } catch (e: Throwable) {
                result.error("W1_ERROR", e.message, null)
            }
        }
    }
}
