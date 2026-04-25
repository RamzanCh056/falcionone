package com.example.falcon_one_demo.bleequp

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference

/**
 * Temporary no-op bridge while BleeqUp vendor AAR is unavailable.
 */
object BleeqUpFlutterBridge {
    const val METHOD_CHANNEL = "bleequp_sdk"
    const val EVENT_CHANNEL = "bleequp_sdk/events"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventSinkRef = AtomicReference<EventChannel.EventSink?>(null)

    fun attach(activity: FlutterActivity, engine: FlutterEngine) {
        val messenger = engine.dartExecutor.binaryMessenger

        EventChannel(messenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSinkRef.set(events)
                }

                override fun onCancel(arguments: Any?) {
                    eventSinkRef.set(null)
                }
            },
        )

        MethodChannel(messenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            try {
                handleMethodCall(call, result)
            } catch (e: Throwable) {
                emit("onError", mapOf("message" to (e.message ?: "native_error")))
                result.error("BLEEQUP_ERROR", e.message, null)
            }
        }
    }

    private fun emit(event: String, extras: Map<String, Any?> = emptyMap()) {
        val sink = eventSinkRef.get() ?: return
        val payload = HashMap<String, Any>(extras.size + 1)
        payload["event"] = event
        extras.forEach { (k, v) ->
            if (v != null) payload[k] = v
        }
        mainHandler.post { sink.success(payload) }
    }

    private fun unavailable(result: MethodChannel.Result) {
        val message = "BleeqUp SDK disabled: missing android/app/libs/bleequp-sdk.aar"
        emit("onError", mapOf("message" to message))
        result.error("BLEEQUP_UNAVAILABLE", message, null)
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initSdk",
            "startScan",
            "connectDevice",
            "startRecording",
            "stopRecording",
            "turnOnWifi",
            "turnOffWifi",
            "downloadFile",
            -> unavailable(result)
            else -> result.notImplemented()
        }
    }
}
