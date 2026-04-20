package com.example.falcon_one_demo.w1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/**
 * Unfiltered BLE scan (all peripherals) with structured logs per advertisement.
 * GATT connect/notify still uses [W1DeviceUuids] when a connection path is wired in.
 */
class W1BleGattCoordinator(
    context: Context,
    private val logger: W1Logger,
    private val uuids: W1DeviceUuids,
    private val sessionIdForLogs: () -> String,
    private val onPipeline: (W1PipelineState, String) -> Unit,
    private val onRecordingCompleteJson: (recordingId: String, extras: Map<String, String?>) -> Unit,
    private val onBleError: (message: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scanner = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logBleDeviceSeen(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) {
                logBleDeviceSeen(r)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logger.e(sessionIdForLogs(), "ble_scan_failed", mapOf("code" to errorCode), null)
            onBleError("scan failed code=$errorCode")
        }
    }

    /** One log line per advertisement (Logcat + JSON) — unfiltered discovery only; no auto-connect. */
    @SuppressLint("MissingPermission")
    private fun logBleDeviceSeen(result: ScanResult) {
        val device = result.device ?: return
        val advertised = result.scanRecord?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val cached = device.name?.trim()?.takeIf { it.isNotEmpty() }
        val name = advertised ?: cached ?: "(no name)"
        logger.i(
            sessionIdForLogs(),
            "ble_device_seen",
            mapOf(
                "name" to name,
                "address" to device.address,
                "rssi" to result.rssi,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_state",
                mapOf("status" to status, "newState" to newState),
            )
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onPipeline(W1PipelineState.BLUETOOTH_CONNECTED, "GATT connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onPipeline(W1PipelineState.IDLE, "GATT disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onBleError("service discovery status=$status")
                return
            }
            val service = gatt.getService(uuids.service)
            if (service == null) {
                onBleError("service ${uuids.service} not found — check W1DeviceUuids")
                return
            }
            val ch = service.getCharacteristic(uuids.recordingStatusCharacteristic)
            if (ch == null) {
                onBleError("notify characteristic missing")
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = ch.getDescriptor(cccdUuid)
            if (descriptor == null) {
                onBleError("CCCD descriptor missing")
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            logger.i(sessionIdForLogs(), "ble_notify_enabled", mapOf("uuid" to ch.uuid.toString()))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != uuids.recordingStatusCharacteristic) return
            val bytes = characteristic.value ?: return
            parseRecordingPayload(bytes)
        }
    }

    private fun parseRecordingPayload(bytes: ByteArray) {
        try {
            val text = bytes.toString(Charsets.UTF_8)
            val jo = JSONObject(text)
            val type = jo.optString("type", "")
            if (type.equals("recording_complete", ignoreCase = true) || jo.has("recordingId")) {
                val id = jo.optString("recordingId", jo.optString("id", ""))
                if (id.isBlank()) {
                    onBleError("recording_complete without recordingId")
                    return
                }
                val extras = mutableMapOf<String, String?>()
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "recordingId" && k != "type") {
                        if (!jo.isNull(k)) extras[k] = jo.optString(k, null)
                    }
                }
                logger.i(sessionIdForLogs(), "ble_recording_complete", mapOf("recordingId" to id))
                onRecordingCompleteJson(id, extras)
            }
        } catch (e: Exception) {
            logger.e(sessionIdForLogs(), "ble_parse_failed", mapOf("rawLen" to bytes.size), e)
            onBleError(e.message ?: "parse")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanIfPermitted() {
        if (adapter == null || !adapter.isEnabled) {
            onBleError("Bluetooth off or unavailable")
            return
        }
        if (!hasConnectPermission()) {
            onBleError("Missing BLUETOOTH_CONNECT / legacy Bluetooth permission")
            return
        }
        if (!hasScanPermission()) {
            onBleError("Missing BLUETOOTH_SCAN / legacy scan permission")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onBleError("LE scanner unavailable")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        logger.i(sessionIdForLogs(), "ble_scan_start", mapOf("mode" to "unfiltered_all_devices"))
        scanner?.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
