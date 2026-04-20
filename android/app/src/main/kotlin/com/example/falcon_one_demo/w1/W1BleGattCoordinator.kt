package com.example.falcon_one_demo.w1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Unfiltered BLE scan (all peripherals) with structured logs per advertisement.
 * [connectToDevice] runs a discovery-only GATT session (log all services/characteristics; optional notifies).
 * Legacy vendor UUID handling remains behind [discoveryMode] == false for future workflows.
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
    private companion object {
        /** Same meaning as [ScanRecord] TX power sentinel when AD 0x0A is absent (AOSP value 127). */
        private const val SCAN_TX_POWER_NOT_PRESENT = 127

        /** Bluetooth SIG assigned UUID for the Client Characteristic Configuration descriptor (enable notify/indicate). */
        private val GattDescriptorUuidClientCharacteristicConfig: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scanner = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    /** When true, GATT callbacks only discover and log (no vendor service assumptions). */
    private var discoveryMode: Boolean = false

    private val pendingNotifyCharacteristics = java.util.ArrayDeque<BluetoothGattCharacteristic>()

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
        val record = result.scanRecord
        val advertised = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val cached = device.name?.trim()?.takeIf { it.isNotEmpty() }
        val name = advertised ?: cached ?: "(no name)"
        val fields = mutableMapOf<String, Any?>(
            "name" to name,
            "address" to device.address,
            "rssi" to result.rssi,
        )
        formatManufacturerData(record)?.let { fields["mfrData"] = it }
        formatAdvertisedServiceUuids(record)?.let { fields["serviceUuids"] = it }
        record?.txPowerLevel?.takeIf { it != SCAN_TX_POWER_NOT_PRESENT }?.let { fields["txPower"] = it }
        if (Build.VERSION.SDK_INT >= 33) {
            val raw = record?.bytes
            if (raw != null && raw.isNotEmpty()) {
                fields["advHex"] = hexPrefix(raw, maxBytes = 31)
            }
        }
        logger.i(sessionIdForLogs(), "ble_device_seen", fields)
    }

    /** `COMPANY:HEX|…` from AD manufacturer blocks (helps identify devices that use "(no name)"). */
    private fun formatManufacturerData(record: ScanRecord?): String? {
        if (record == null) return null
        @Suppress("DEPRECATION")
        val mfr = record.manufacturerSpecificData ?: return null
        if (mfr.size() == 0) return null
        return buildString {
            for (i in 0 until mfr.size()) {
                if (isNotEmpty()) append('|')
                val id = mfr.keyAt(i) and 0xFFFF
                val data = mfr.valueAt(i) ?: continue
                append(String.format("%04X:", id))
                append(hexPrefix(data, maxBytes = 20))
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun formatAdvertisedServiceUuids(record: ScanRecord?): String? {
        val list = record?.serviceUuids ?: return null
        if (list.isEmpty()) return null
        return list.asSequence()
            .take(16)
            .joinToString(",") { it.uuid.toString() }
            .take(900)
    }

    private fun hexPrefix(bytes: ByteArray, maxBytes: Int): String {
        val n = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(n * 2 + 1)
        for (i in 0 until n) {
            sb.append(String.format("%02X", bytes[i]))
        }
        if (bytes.size > maxBytes) sb.append("...")
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val stateName = gattConnectionStateName(newState)
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_connection_state",
                mapOf(
                    "device" to gatt.device.address,
                    "status" to status,
                    "newState" to newState,
                    "stateName" to stateName,
                    "discoveryMode" to discoveryMode,
                ),
            )
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logger.i(
                        sessionIdForLogs(),
                        "ble_gatt_connected",
                        mapOf("device" to gatt.device.address),
                    )
                } else {
                    logger.w(
                        sessionIdForLogs(),
                        "ble_gatt_connected_nonzero_status",
                        mapOf("device" to gatt.device.address, "status" to status),
                    )
                }
                onPipeline(W1PipelineState.BLUETOOTH_CONNECTED, "GATT connected")
                val started = gatt.discoverServices()
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_discover_services_requested",
                    mapOf("started" to started),
                )
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pendingNotifyCharacteristics.clear()
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_disconnected",
                    mapOf("device" to gatt.device.address, "status" to status),
                )
                onPipeline(W1PipelineState.IDLE, "GATT disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.e(
                    sessionIdForLogs(),
                    "ble_gatt_service_discovery_failed",
                    mapOf("status" to status),
                    null,
                )
                onBleError("service discovery status=$status")
                return
            }
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_services_discovered_ok",
                mapOf("device" to gatt.device.address),
            )
            if (discoveryMode) {
                logAllGattStructure(gatt)
                beginNotifyEnableQueue(gatt)
            } else {
                legacyEnableRecordingNotifications(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!discoveryMode) return
            if (descriptor.uuid != GattDescriptorUuidClientCharacteristicConfig) return
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_descriptor_write",
                mapOf(
                    "descriptorUuid" to descriptor.uuid.toString(),
                    "charUuid" to descriptor.characteristic.uuid.toString(),
                    "serviceUuid" to descriptor.characteristic.service.uuid.toString(),
                    "status" to status,
                ),
            )
            enableNextNotifyDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (discoveryMode) {
                logCharacteristicChanged(characteristic)
                return
            }
            if (characteristic.uuid != uuids.recordingStatusCharacteristic) return
            val bytes = characteristic.value ?: return
            parseRecordingPayload(bytes)
        }
    }

    private fun gattConnectionStateName(newState: Int): String = when (newState) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($newState)"
    }

    private fun logAllGattStructure(gatt: BluetoothGatt) {
        val services = gatt.services ?: emptyList()
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_discovery_summary",
            mapOf("serviceCount" to services.size, "device" to gatt.device.address),
        )
        for (svc in services) {
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_service",
                mapOf("uuid" to svc.uuid.toString()),
            )
            for (ch in svc.characteristics) {
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_characteristic",
                    mapOf(
                        "serviceUuid" to svc.uuid.toString(),
                        "uuid" to ch.uuid.toString(),
                        "properties" to characteristicPropertyNames(ch.properties),
                    ),
                )
            }
        }
    }

    private fun characteristicPropertyNames(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) parts.add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) parts.add("SIGNED_WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) parts.add("EXTENDED_PROPS")
        return parts.joinToString(",")
    }

    private fun beginNotifyEnableQueue(gatt: BluetoothGatt) {
        pendingNotifyCharacteristics.clear()
        for (svc in gatt.services ?: emptyList()) {
            for (ch in svc.characteristics) {
                val notify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val indicate = (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (notify || indicate) pendingNotifyCharacteristics.addLast(ch)
            }
        }
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_notify_queue_built",
            mapOf("count" to pendingNotifyCharacteristics.size),
        )
        enableNextNotifyDescriptor(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotifyDescriptor(gatt: BluetoothGatt) {
        val ch = pendingNotifyCharacteristics.pollFirst()
        if (ch == null) {
            logger.i(sessionIdForLogs(), "ble_gatt_notify_enable_complete", emptyMap())
            return
        }
        val cccd = ch.getDescriptor(GattDescriptorUuidClientCharacteristicConfig)
        if (cccd == null) {
            logger.w(
                sessionIdForLogs(),
                "ble_gatt_cccd_missing",
                mapOf("charUuid" to ch.uuid.toString(), "serviceUuid" to ch.service.uuid.toString()),
            )
            enableNextNotifyDescriptor(gatt)
            return
        }
        if (!gatt.setCharacteristicNotification(ch, true)) {
            logger.w(
                sessionIdForLogs(),
                "ble_gatt_set_notification_failed",
                mapOf("charUuid" to ch.uuid.toString()),
            )
            enableNextNotifyDescriptor(gatt)
            return
        }
        val payload = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        cccd.value = payload
        val writeOk = gatt.writeDescriptor(cccd)
        if (!writeOk) {
            logger.w(
                sessionIdForLogs(),
                "ble_gatt_write_descriptor_failed",
                mapOf("charUuid" to ch.uuid.toString()),
            )
            enableNextNotifyDescriptor(gatt)
        }
    }

    private fun logCharacteristicChanged(characteristic: BluetoothGattCharacteristic) {
        val bytes = characteristic.value ?: ByteArray(0)
        val hex = hexPrefix(bytes, maxBytes = 64)
        val utf8 = bytesToUtf8Preview(bytes)
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_characteristic_changed",
            mapOf(
                "serviceUuid" to characteristic.service.uuid.toString(),
                "charUuid" to characteristic.uuid.toString(),
                "len" to bytes.size,
                "hex" to hex,
                "utf8" to utf8,
            ),
        )
    }

    private fun bytesToUtf8Preview(bytes: ByteArray, maxChars: Int = 400): String {
        if (bytes.isEmpty()) return ""
        return try {
            val s = bytes.toString(Charsets.UTF_8)
            val cleaned = s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), ".")
            if (cleaned.length > maxChars) cleaned.take(maxChars) + "..." else cleaned
        } catch (e: Exception) {
            "(utf8: ${e.javaClass.simpleName})"
        }
    }

    @SuppressLint("MissingPermission")
    private fun legacyEnableRecordingNotifications(gatt: BluetoothGatt) {
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
        val descriptor = ch.getDescriptor(GattDescriptorUuidClientCharacteristicConfig)
        if (descriptor == null) {
            onBleError("CCCD descriptor missing")
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
        logger.i(sessionIdForLogs(), "ble_notify_enabled", mapOf("uuid" to ch.uuid.toString()))
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

    /**
     * Connects by public MAC, then discovers and logs every service and characteristic.
     * Uses [BluetoothDevice.connectGatt] with autoConnect `false` (active connection).
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(macAddress: String) {
        if (adapter == null || !adapter.isEnabled) {
            onBleError("Bluetooth off or unavailable")
            return
        }
        if (!hasConnectPermission()) {
            onBleError("Missing BLUETOOTH_CONNECT / legacy Bluetooth permission")
            return
        }
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        pendingNotifyCharacteristics.clear()
        val normalized = macAddress.trim().uppercase(Locale.US)
        val device = try {
            adapter.getRemoteDevice(normalized)
        } catch (e: IllegalArgumentException) {
            logger.e(sessionIdForLogs(), "ble_connect_invalid_mac", mapOf("mac" to macAddress), e)
            onBleError("invalid MAC: ${e.message}")
            return
        }
        discoveryMode = true
        logger.i(
            sessionIdForLogs(),
            "ble_connect_attempt",
            mapOf("address" to normalized),
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
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
        discoveryMode = false
        pendingNotifyCharacteristics.clear()
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
