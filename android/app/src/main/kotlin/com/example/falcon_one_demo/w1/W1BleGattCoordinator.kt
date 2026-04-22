package com.example.falcon_one_demo.w1

import android.Manifest
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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unfiltered BLE scan (all peripherals) with structured logs per advertisement.
 *
 * **W1 GATT connect** mirrors the usual FlutterBluePlus-style flow (this app uses native Android BLE, not FBP):
 * start LE scan (15 s) → match **MAC** or **local name** → stop scan → GATT teardown + 500 ms →
 * [BluetoothDevice.connectGatt] on the **scanned** [BluetoothDevice] (TRANSPORT_LE, autoConnect false) →
 * on [BluetoothProfile.STATE_CONNECTED] with [BluetoothGatt.GATT_SUCCESS] → [BluetoothGatt.discoverServices] →
 * on early disconnect / watchdog: wait 2 s → full scan again (no direct MAC-only reconnect).
 *
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
    /** Flutter / UI: scan phase updates (String keys, JSON-serializable values). */
    private val onBleScanUi: (Map<String, Any?>) -> Unit = { _ -> },
) {
    private companion object {
        /** Same meaning as [ScanRecord] TX power sentinel when AD 0x0A is absent (AOSP value 127). */
        private const val SCAN_TX_POWER_NOT_PRESENT = 127

        /** Bluetooth SIG assigned UUID for the Client Characteristic Configuration descriptor (enable notify/indicate). */
        private val GattDescriptorUuidClientCharacteristicConfig: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PROBE_CONNECT_TIMEOUT_MS = 15_000L
        private const val PROBE_RETRY_DELAY_MS = 450L

        /** Default W1 advertised / GAP local name (match is case-insensitive). */
        private const val DEFAULT_W1_BLE_LOCAL_NAME = "SSJ-ZXAN9A1"

        /** LE scan window when resolving a connectable advertisement before GATT (FlutterBluePlus-style timeout). */
        private const val SCAN_FOR_CONNECT_TIMEOUT_MS = 15_000L

        /** After a failed GATT session, wait before starting a fresh scan (no direct MAC reconnect). */
        private const val SCAN_CONNECT_DISCONNECT_RETRY_MS = 2_000L

        /** GATT teardown ([disconnect]/[close]) before each [connectGatt] (stale handle cleanup). */
        private const val GATT_CLEANUP_BEFORE_CONNECT_MS = 500L

        /** Max number of scan cycles (each can include one GATT attempt) before giving up. */
        private const val MAX_SCAN_CONNECT_CYCLES = 6

        /** If no CONNECTED within this window after [connectGatt], close and restart scan flow. */
        private const val SCAN_CONNECT_GATT_WATCHDOG_MS = 32_000L

        /** After this many filtered scan windows with no target match, run one diagnostic scan (see [enterDiagnosticUnfilteredScanPhase]). */
        private const val FILTERED_SCAN_TIMEOUTS_BEFORE_DIAGNOSTIC = 2

        /** One-shot unfiltered discovery window to log every peripheral (names + MAC + RSSI). */
        private const val DIAGNOSTIC_SCAN_MS = 10_000L

        /** Ignore connect if matched advertisement is at or below this RSSI (ghost / edge of range). */
        private const val MIN_RSSI_TO_CONNECT_DB = -90
    }

    private data class ScanAgg(var bestRssi: Int, var displayNameAtBest: String)

    private val scanAccumulator = ConcurrentHashMap<String, ScanAgg>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectProbeTimeoutRunnable = Runnable { onConnectProbeTimeout() }

    private var connectProbeActive: Boolean = false
    private var connectProbeList: List<Pair<String, Int>> = emptyList()
    private var connectProbeIndex: Int = 0
    private var connectProbeAttemptMac: String? = null
    private var connectProbeSawConnectedForAttempt: Boolean = false
    private var connectProbeFailureHandledForAttempt: Boolean = false
    private var probeAdvanceRunnable: Runnable? = null
    private val probeLock = Any()

    /** Scan-first connect session ([connectToDevice] / [forceBleSafeConnect]); no direct MAC-only GATT for W1. */
    private var scanConnectSessionActive: Boolean = false
    private var scanConnectAwaitingAdvertisement: Boolean = false
    private var scanConnectTargetMac: String? = null
    private var scanConnectTargetLocalName: String? = null
    private var scanConnectBondPairingConflictWarn: Boolean = true
    private var scanConnectForceSafePath: Boolean = false
    private var scanConnectAutoRetryEnabled: Boolean = false
    private var scanConnectAttemptNumber: Int = 0
    private var scanForConnectTimeoutRunnable: Runnable? = null
    private var scanConnectDisconnectRetryRunnable: Runnable? = null
    private var diagnosticScanTimeoutRunnable: Runnable? = null
    private val scanConnectGattWatchdogRunnable = Runnable { onScanConnectGattWatchdogTimeout() }

    private var scanConnectFilteredTimeoutsWithoutMatch: Int = 0
    private var scanConnectDiagnosticDone: Boolean = false
    private var scanConnectDiagnosticScanActive: Boolean = false

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun emitBleScanUi(fields: Map<String, Any?>) {
        try {
            onBleScanUi(fields)
        } catch (_: Exception) {
        }
    }
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
        recordScanSample(device.address, result.rssi, name)
        if (scanConnectDiagnosticScanActive) {
            logger.i(
                sessionIdForLogs(),
                "ble_diagnostic_device",
                mapOf(
                    "msg" to "FOUND: ${device.address} | name: $name | rssi: ${result.rssi}",
                    "address" to device.address,
                    "name" to name,
                    "rssi" to result.rssi,
                ),
            )
            return
        }
        maybeOfferScanConnectCandidate(result)
    }

    private fun recordScanSample(address: String, rssi: Int, displayName: String) {
        val key = address.uppercase(Locale.US)
        scanAccumulator.merge(key, ScanAgg(rssi, displayName)) { old, incoming ->
            if (incoming.bestRssi > old.bestRssi) {
                ScanAgg(incoming.bestRssi, incoming.displayNameAtBest)
            } else {
                old
            }
        }
    }

    private fun deviceMatchesScanConnectTarget(device: BluetoothDevice, record: ScanRecord?): Boolean {
        val mac = scanConnectTargetMac ?: return false
        if (device.address.equals(mac, ignoreCase = true)) return true
        val hint = scanConnectTargetLocalName ?: return false
        if (hint.isEmpty()) return false
        val adv = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val cached = device.name?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = adv ?: cached ?: return false
        return resolved.equals(hint, ignoreCase = true)
    }

    private fun maybeOfferScanConnectCandidate(result: ScanResult) {
        if (!scanConnectSessionActive || !scanConnectAwaitingAdvertisement) return
        val device = result.device ?: return
        val record = result.scanRecord
        if (!deviceMatchesScanConnectTarget(device, record)) return
        if (result.rssi <= MIN_RSSI_TO_CONNECT_DB) {
            logger.i(
                sessionIdForLogs(),
                "ble_scan_connect_target_weak_rssi",
                mapOf(
                    "address" to device.address,
                    "rssi" to result.rssi,
                    "minRssiDb" to MIN_RSSI_TO_CONNECT_DB,
                    "msg" to "Found target but RSSI too weak (${result.rssi} dBm ≤ $MIN_RSSI_TO_CONNECT_DB) — skipping connect, keep scanning",
                ),
            )
            return
        }
        mainHandler.post { commitScanConnectTargetFound(device, result.rssi) }
    }

    @SuppressLint("MissingPermission")
    private fun commitScanConnectTargetFound(device: BluetoothDevice, rssi: Int) {
        if (!scanConnectSessionActive || !scanConnectAwaitingAdvertisement) return
        scanConnectAwaitingAdvertisement = false
        scanForConnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanForConnectTimeoutRunnable = null
        stopScan()
        emitBleScanUi(
            mapOf(
                "phase" to "connecting",
                "attempt" to scanConnectAttemptNumber,
                "maxAttempts" to MAX_SCAN_CONNECT_CYCLES,
                "detail" to "Device found! Connecting…",
                "rssi" to rssi,
            ),
        )
        logger.i(
            sessionIdForLogs(),
            "ble_scan_connect_target_found",
            mapOf(
                "address" to device.address,
                "rssi" to rssi,
                "bondState" to device.bondState,
                "bondStateName" to bondStateName(device.bondState),
                "msg" to "Matched scan result; stopping scan before GATT (scanned device object, not remote MAC only)",
            ),
        )
        val captured = device
        gattCleanupBeforeConnectRunnable(
            Runnable {
                if (!scanConnectSessionActive) return@Runnable
                openGattDiscoveryConnectionFromScanResult(
                    captured,
                    logBleConnectAttempt = true,
                    bondPairingConflictWarn = scanConnectBondPairingConflictWarn,
                    forceSafeConnectPath = scanConnectForceSafePath,
                )
            },
        )
    }

    private fun gattCleanupBeforeConnectRunnable(then: Runnable) {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        pendingNotifyCharacteristics.clear()
        mainHandler.postDelayed(then, GATT_CLEANUP_BEFORE_CONNECT_MS)
    }

    private fun cancelScanConnectDelayedRunnables() {
        scanForConnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanForConnectTimeoutRunnable = null
        scanConnectDisconnectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        scanConnectDisconnectRetryRunnable = null
        diagnosticScanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        diagnosticScanTimeoutRunnable = null
        mainHandler.removeCallbacks(scanConnectGattWatchdogRunnable)
    }

    private fun cancelScanConnectSession() {
        cancelScanConnectDelayedRunnables()
        scanConnectSessionActive = false
        scanConnectAwaitingAdvertisement = false
        scanConnectAutoRetryEnabled = false
        scanConnectDiagnosticScanActive = false
        scanConnectTargetMac = null
        scanConnectTargetLocalName = null
        scanConnectAttemptNumber = 0
        scanConnectFilteredTimeoutsWithoutMatch = 0
        scanConnectDiagnosticDone = false
    }

    @SuppressLint("MissingPermission")
    private fun enterScanPhaseForConnect() {
        if (!scanConnectSessionActive) return
        if (adapter == null || !adapter.isEnabled) {
            onBleError("Bluetooth off or unavailable")
            cancelScanConnectSession()
            return
        }
        scanConnectAttemptNumber++
        if (scanConnectAttemptNumber > MAX_SCAN_CONNECT_CYCLES) {
            val hint =
                "W1 device not found after $MAX_SCAN_CONNECT_CYCLES scan attempts. " +
                    "Please ensure the device is powered on and not connected to another phone, then tap Retry."
            emitBleScanUi(
                mapOf(
                    "phase" to "exhausted",
                    "attempt" to MAX_SCAN_CONNECT_CYCLES,
                    "maxAttempts" to MAX_SCAN_CONNECT_CYCLES,
                    "detail" to "Device not found — is W1 powered on and advertising?",
                    "userHint" to hint,
                ),
            )
            onBleError("BLE scan/connect exhausted after $MAX_SCAN_CONNECT_CYCLES attempts")
            cancelScanConnectSession()
            return
        }
        scanConnectAwaitingAdvertisement = true
        stopScan()
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onBleError("LE scanner unavailable")
            cancelScanConnectSession()
            return
        }
        emitBleScanUi(
            mapOf(
                "phase" to "scanning",
                "attempt" to scanConnectAttemptNumber,
                "maxAttempts" to MAX_SCAN_CONNECT_CYCLES,
                "detail" to "Scanning… (attempt ${scanConnectAttemptNumber} of $MAX_SCAN_CONNECT_CYCLES)",
            ),
        )
        val timeout = Runnable {
            scanForConnectTimeoutRunnable = null
            if (!scanConnectSessionActive || !scanConnectAwaitingAdvertisement) return@Runnable
            scanConnectAwaitingAdvertisement = false
            stopScan()
            logger.w(
                sessionIdForLogs(),
                "ble_scan_connect_timeout",
                mapOf(
                    "timeoutMs" to SCAN_FOR_CONNECT_TIMEOUT_MS,
                    "mac" to (scanConnectTargetMac ?: ""),
                    "localName" to (scanConnectTargetLocalName ?: ""),
                    "msg" to "No advertisement from target within scan window — device must be advertising",
                ),
            )
            scanConnectFilteredTimeoutsWithoutMatch++
            if (scanConnectFilteredTimeoutsWithoutMatch >= FILTERED_SCAN_TIMEOUTS_BEFORE_DIAGNOSTIC &&
                !scanConnectDiagnosticDone &&
                scanConnectSessionActive &&
                scanConnectAutoRetryEnabled
            ) {
                scanConnectDiagnosticDone = true
                enterDiagnosticUnfilteredScanPhase()
                return@Runnable
            }
            scheduleScanConnectFullRecovery(reason = "scan_timeout")
        }
        scanForConnectTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCAN_FOR_CONNECT_TIMEOUT_MS)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        logger.i(
            sessionIdForLogs(),
            "ble_scan_for_connect_start",
            mapOf(
                "attempt" to scanConnectAttemptNumber,
                "maxAttempts" to MAX_SCAN_CONNECT_CYCLES,
                "timeoutMs" to SCAN_FOR_CONNECT_TIMEOUT_MS,
                "mac" to (scanConnectTargetMac ?: ""),
                "localName" to (scanConnectTargetLocalName ?: ""),
            ),
        )
        scanner?.startScan(null, settings, scanCallback)
    }

    private fun beginW1ScanBasedConnect(
        macAddress: String,
        localNameHint: String?,
        bondPairingConflictWarn: Boolean,
        forceSafeConnectPath: Boolean,
    ) {
        stopConnectProbe()
        cancelScanConnectSession()
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
        if (!hasLocationPermissionForBle()) {
            logger.w(
                sessionIdForLogs(),
                "ble_connect_location_permission",
                mapOf("msg" to "No ACCESS_FINE/COARSE_LOCATION grant; BLE may be unreliable on some devices"),
            )
        }
        if (!isSystemLocationEnabled()) {
            logger.w(
                sessionIdForLogs(),
                "ble_connect_location_services_off",
                mapOf("msg" to "System location is OFF; turn Location ON for reliable BLE on many OEMs"),
            )
        }
        val normalized = macAddress.trim().uppercase(Locale.US)
        try {
            adapter.getRemoteDevice(normalized)
        } catch (e: IllegalArgumentException) {
            logger.e(sessionIdForLogs(), "ble_connect_invalid_mac", mapOf("mac" to macAddress), e)
            onBleError("invalid MAC: ${e.message}")
            return
        }
        scanConnectSessionActive = true
        scanConnectTargetMac = normalized
        scanConnectTargetLocalName = localNameHint?.trim()?.takeIf { it.isNotEmpty() }
        scanConnectBondPairingConflictWarn = bondPairingConflictWarn
        scanConnectForceSafePath = forceSafeConnectPath
        scanConnectAutoRetryEnabled = true
        scanConnectAttemptNumber = 0
        scanConnectFilteredTimeoutsWithoutMatch = 0
        scanConnectDiagnosticDone = false
        scanConnectDiagnosticScanActive = false
        logger.i(
            sessionIdForLogs(),
            "ble_scan_connect_flow_start",
            mapOf(
                "mac" to normalized,
                "localName" to (scanConnectTargetLocalName ?: ""),
                "msg" to "GATT cleanup then LE scan → connect using scanned BluetoothDevice",
            ),
        )
        emitBleScanUi(mapOf("phase" to "preparing", "detail" to "Preparing (GATT cleanup)…"))
        gattCleanupBeforeConnectRunnable(Runnable { enterScanPhaseForConnect() })
    }

    /**
     * Same hardware unfiltered LE scan as normal discovery, but for [DIAGNOSTIC_SCAN_MS] we only log
     * every peripheral ([ble_diagnostic_device]) and do not match the W1 target — helps when filtered
     * match never fires on some stacks.
     */
    @SuppressLint("MissingPermission")
    private fun enterDiagnosticUnfilteredScanPhase() {
        if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) return
        cancelScanConnectDelayedRunnables()
        scanConnectDiagnosticScanActive = true
        scanConnectAwaitingAdvertisement = false
        stopScan()
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            scanConnectDiagnosticScanActive = false
            scheduleScanConnectFullRecovery(reason = "diagnostic_scanner_unavailable")
            return
        }
        val leScanner = ad.bluetoothLeScanner
        if (leScanner == null) {
            scanConnectDiagnosticScanActive = false
            scheduleScanConnectFullRecovery(reason = "diagnostic_scanner_unavailable")
            return
        }
        scanner = leScanner
        logger.i(
            sessionIdForLogs(),
            "ble_diagnostic_scan_start",
            mapOf(
                "durationMs" to DIAGNOSTIC_SCAN_MS,
                "msg" to "Unfiltered discovery scan — logging FOUND: MAC | name | rssi for all advertisements",
            ),
        )
        emitBleScanUi(
            mapOf(
                "phase" to "diagnostic",
                "attempt" to scanConnectAttemptNumber,
                "maxAttempts" to MAX_SCAN_CONNECT_CYCLES,
                "detail" to "Diagnostic scan (no target filter) — logging all nearby devices for 10s",
            ),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val done = Runnable {
            diagnosticScanTimeoutRunnable = null
            scanConnectDiagnosticScanActive = false
            stopScan()
            logger.i(
                sessionIdForLogs(),
                "ble_diagnostic_scan_complete",
                mapOf("msg" to "Diagnostic scan finished; resuming filtered W1 scan flow after delay"),
            )
            scheduleScanConnectFullRecovery(reason = "post_diagnostic")
        }
        diagnosticScanTimeoutRunnable = done
        mainHandler.postDelayed(done, DIAGNOSTIC_SCAN_MS)
        leScanner.startScan(null, settings, scanCallback)
    }

    private fun scheduleScanConnectFullRecovery(reason: String) {
        if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) {
            cancelScanConnectSession()
            return
        }
        scanConnectDisconnectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            scanConnectDisconnectRetryRunnable = null
            if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) return@Runnable
            logger.i(
                sessionIdForLogs(),
                "ble_scan_connect_recovery",
                mapOf("reason" to reason, "delayMs" to SCAN_CONNECT_DISCONNECT_RETRY_MS),
            )
            gattCleanupBeforeConnectRunnable(Runnable { enterScanPhaseForConnect() })
        }
        scanConnectDisconnectRetryRunnable = r
        mainHandler.postDelayed(r, SCAN_CONNECT_DISCONNECT_RETRY_MS)
    }

    private fun onScanConnectGattWatchdogTimeout() {
        if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) return
        logger.w(
            sessionIdForLogs(),
            "ble_scan_connect_gatt_watchdog",
            mapOf(
                "timeoutMs" to SCAN_CONNECT_GATT_WATCHDOG_MS,
                "msg" to "No stable GATT connection within watchdog; closing and restarting scan flow",
            ),
        )
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        scheduleScanConnectFullRecovery(reason = "gatt_watchdog")
    }

    private fun markScanConnectGattReady() {
        cancelScanConnectDelayedRunnables()
        scanConnectAutoRetryEnabled = false
        scanConnectSessionActive = false
        scanConnectAwaitingAdvertisement = false
        scanConnectTargetMac = null
        scanConnectTargetLocalName = null
        emitBleScanUi(
            mapOf(
                "phase" to "connected",
                "detail" to "Connected — GATT services discovered",
                "userHint" to "Connected ✅",
            ),
        )
        logger.i(
            sessionIdForLogs(),
            "ble_scan_connect_ready",
            mapOf("msg" to "GATT services discovered; scan→connect session complete"),
        )
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
            if (newState == BluetoothProfile.STATE_CONNECTING) {
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_connect_started",
                    mapOf(
                        "device" to gatt.device.address,
                        "bondState" to gatt.device.bondState,
                        "bondStateName" to bondStateName(gatt.device.bondState),
                        "status" to status,
                    ),
                )
            }
            logger.i(
                sessionIdForLogs(),
                "ble_gatt_on_connection_state_change",
                mapOf(
                    "device" to gatt.device.address,
                    "status" to status,
                    "statusLabel" to gattStatusLabel(status),
                    "newState" to newState,
                    "newStateName" to stateName,
                    "discoveryMode" to discoveryMode,
                    "scanConnectSessionActive" to scanConnectSessionActive,
                ),
            )
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
                val skipPostConnectWork = handleConnectProbeConnected(gatt, status)
                if (skipPostConnectWork) {
                    return
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.removeCallbacks(scanConnectGattWatchdogRunnable)
                    logger.i(
                        sessionIdForLogs(),
                        "ble_gatt_connected",
                        mapOf("device" to gatt.device.address),
                    )
                    onPipeline(W1PipelineState.BLUETOOTH_CONNECTED, "GATT connected")
                    val started = gatt.discoverServices()
                    logger.i(
                        sessionIdForLogs(),
                        "ble_gatt_discover_services_requested",
                        mapOf("started" to started),
                    )
                } else {
                    logger.w(
                        sessionIdForLogs(),
                        "ble_gatt_connected_nonzero_status",
                        mapOf(
                            "device" to gatt.device.address,
                            "status" to status,
                            "statusLabel" to gattStatusLabel(status),
                            "msg" to "Skipping discoverServices until GATT_SUCCESS",
                        ),
                    )
                    maybeScheduleScanConnectRecoveryFromConnection(
                        reason = "connected_non_success_status",
                        disconnectFirst = true,
                    )
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_connect_stopped",
                    mapOf(
                        "device" to gatt.device.address,
                        "status" to status,
                        "statusLabel" to gattStatusLabel(status),
                        "reason" to "DISCONNECTED",
                    ),
                )
                handleConnectProbeDisconnected(gatt, status)
                pendingNotifyCharacteristics.clear()
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_disconnected",
                    mapOf("device" to gatt.device.address, "status" to status),
                )
                val suppressIdle = maybeScheduleScanConnectRecoveryOnDisconnect(gatt, status)
                if (!suppressIdle) {
                    onPipeline(W1PipelineState.IDLE, "GATT disconnected")
                }
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
                maybeScheduleScanConnectRecoveryFromConnection(reason = "service_discovery_failed", disconnectFirst = false)
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
                if (scanConnectSessionActive) {
                    markScanConnectGattReady()
                }
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

    private fun gattStatusLabel(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
        0x01 -> "GATT_INVALID_HANDLE"
        0x02 -> "GATT_READ_NOT_PERMIT"
        0x03 -> "GATT_WRITE_NOT_PERMIT"
        0x05 -> "GATT_INSUF_AUTHENTICATION"
        0x06 -> "GATT_REQ_NOT_SUPPORTED"
        0x08 -> "GATT_CONN_TIMEOUT"
        0x13 -> "GATT_CONN_FAIL_ESTABLISH" // 19
        0x16 -> "GATT_CONN_TERMINATE_LOCAL_HOST" // 22
        0x15 -> "GATT_CONN_TERMINATE_PEER_USER" // 21
        133 -> "GATT_STATUS_133"
        0x93 -> "GATT_CONN_TIMEOUT_OEM" // 147 — common ~30s Android stack timeout
        else -> "STATUS_0x${Integer.toHexString(status)}"
    }

    private fun bondStateName(bondState: Int): String = when (bondState) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> "UNKNOWN($bondState)"
    }

    private fun isSystemLocationEnabled(): Boolean {
        return try {
            Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        } catch (_: Exception) {
            true
        }
    }

    private fun hasLocationPermissionForBle(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** @return true to suppress IDLE (scan-based auto-retry scheduled). */
    private fun maybeScheduleScanConnectRecoveryOnDisconnect(gatt: BluetoothGatt, status: Int): Boolean {
        if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) return false
        logger.w(
            sessionIdForLogs(),
            "ble_scan_connect_disconnect_will_rescan",
            mapOf(
                "device" to gatt.device.address,
                "status" to status,
                "statusLabel" to gattStatusLabel(status),
                "msg" to "Will restart full scan→connect flow after ${SCAN_CONNECT_DISCONNECT_RETRY_MS}ms (no direct MAC reconnect)",
            ),
        )
        scheduleScanConnectFullRecovery(reason = "gatt_disconnected_${status}")
        return true
    }

    private fun maybeScheduleScanConnectRecoveryFromConnection(reason: String, disconnectFirst: Boolean = false) {
        if (!scanConnectSessionActive || !scanConnectAutoRetryEnabled) return
        if (disconnectFirst) {
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
        }
        scheduleScanConnectFullRecovery(reason = reason)
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

    private fun stopConnectProbe() {
        cancelScanConnectSession()
        mainHandler.removeCallbacks(connectProbeTimeoutRunnable)
        cancelProbeAdvanceOnly()
        connectProbeActive = false
        connectProbeList = emptyList()
        connectProbeIndex = 0
        connectProbeAttemptMac = null
        connectProbeSawConnectedForAttempt = false
        connectProbeFailureHandledForAttempt = false
    }

    private fun cancelProbeAdvanceOnly() {
        probeAdvanceRunnable?.let { mainHandler.removeCallbacks(it) }
        probeAdvanceRunnable = null
    }

    /** @return true if the outer [onConnectionStateChange] should skip pipeline + service discovery. */
    private fun handleConnectProbeConnected(gatt: BluetoothGatt, status: Int): Boolean {
        if (!connectProbeActive) return false
        val addr = gatt.device.address.uppercase(Locale.US)
        if (addr != connectProbeAttemptMac) return false
        if (connectProbeSawConnectedForAttempt) return false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger.w(
                sessionIdForLogs(),
                "ble_probe_connection_result",
                mapOf(
                    "result" to "failure",
                    "mac" to addr,
                    "status" to status,
                    "msg" to "Probe connection failed: $addr (connected with non-success status=$status)",
                ),
            )
            connectProbeAttemptMac = null
            val handled = synchronized(probeLock) {
                if (connectProbeFailureHandledForAttempt) false
                else {
                    connectProbeFailureHandledForAttempt = true
                    true
                }
            }
            if (handled) {
                mainHandler.removeCallbacks(connectProbeTimeoutRunnable)
                val next = connectProbeIndex + 1
                scheduleTryProbeAt(next)
            }
            return true
        }
        connectProbeSawConnectedForAttempt = true
        mainHandler.removeCallbacks(connectProbeTimeoutRunnable)
        val rssi = connectProbeList.getOrNull(connectProbeIndex)?.second ?: 0
        logger.i(
            sessionIdForLogs(),
            "ble_probe_connection_result",
            mapOf(
                "result" to "success",
                "mac" to addr,
                "rssi" to rssi,
                "msg" to "Probe connection succeeded: $addr (RSSI $rssi)",
            ),
        )
        cancelProbeAdvanceOnly()
        connectProbeActive = false
        connectProbeAttemptMac = null
        return false
    }

    private fun handleConnectProbeDisconnected(gatt: BluetoothGatt, status: Int) {
        if (!connectProbeActive) return
        val addr = gatt.device.address.uppercase(Locale.US)
        if (addr != connectProbeAttemptMac) return
        if (connectProbeSawConnectedForAttempt) return
        val shouldHandle = synchronized(probeLock) {
            if (connectProbeFailureHandledForAttempt) {
                false
            } else {
                connectProbeFailureHandledForAttempt = true
                true
            }
        }
        if (!shouldHandle) return
        mainHandler.removeCallbacks(connectProbeTimeoutRunnable)
        logger.w(
            sessionIdForLogs(),
            "ble_probe_connection_result",
            mapOf(
                "result" to "failure",
                "mac" to addr,
                "status" to status,
                "msg" to "Probe connection failed: $addr (status=$status)",
            ),
        )
        connectProbeAttemptMac = null
        val next = connectProbeIndex + 1
        scheduleTryProbeAt(next)
    }

    private fun onConnectProbeTimeout() {
        if (!connectProbeActive) return
        if (connectProbeSawConnectedForAttempt) return
        val addr = connectProbeAttemptMac ?: return
        val shouldHandle = synchronized(probeLock) {
            if (connectProbeFailureHandledForAttempt) {
                false
            } else {
                connectProbeFailureHandledForAttempt = true
                true
            }
        }
        if (!shouldHandle) return
        logger.w(
            sessionIdForLogs(),
            "ble_probe_connection_result",
            mapOf(
                "result" to "failure",
                "reason" to "timeout",
                "mac" to addr,
                "msg" to "Probe connection failed: $addr (timeout)",
            ),
        )
        connectProbeAttemptMac = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        val next = connectProbeIndex + 1
        scheduleTryProbeAt(next)
    }

    private fun scheduleTryProbeAt(nextIndex: Int) {
        cancelProbeAdvanceOnly()
        val r = Runnable {
            probeAdvanceRunnable = null
            tryConnectProbeAt(nextIndex)
        }
        probeAdvanceRunnable = r
        mainHandler.postDelayed(r, PROBE_RETRY_DELAY_MS)
    }

    /**
     * From accumulated scan results: unnamed devices with RSSI > -70, strongest first, try GATT to up to 3 MACs.
     * Stops after the first successful connection.
     */
    fun runAnonymousConnectProbe() {
        stopConnectProbe()
        if (adapter == null || !adapter.isEnabled) {
            onBleError("Bluetooth off or unavailable")
            return
        }
        if (!hasConnectPermission()) {
            onBleError("Missing BLUETOOTH_CONNECT / legacy Bluetooth permission")
            return
        }
        stopScan()
        val built = scanAccumulator.entries
            .map { (addr, acc) -> addr.uppercase(Locale.US) to acc }
            .filter { (_, acc) -> acc.displayNameAtBest == "(no name)" && acc.bestRssi > -70 }
            .sortedByDescending { (_, acc) -> acc.bestRssi }
            .take(3)
            .map { (addr, acc) -> addr to acc.bestRssi }
        if (built.isEmpty()) {
            logger.w(
                sessionIdForLogs(),
                "ble_probe_no_candidates",
                mapOf(
                    "accumulatedDevices" to scanAccumulator.size,
                    "msg" to "No scan hits with name (no name) and RSSI > -70",
                ),
            )
            return
        }
        connectProbeList = built
        connectProbeIndex = 0
        connectProbeActive = true
        logger.i(
            sessionIdForLogs(),
            "ble_probe_plan",
            mapOf(
                "attempts" to built.size,
                "msg" to built.joinToString { "${it.first} @ ${it.second} dBm" },
            ),
        )
        tryConnectProbeAt(0)
    }

    private fun tryConnectProbeAt(index: Int) {
        if (!connectProbeActive) return
        if (index >= connectProbeList.size) {
            logger.i(
                sessionIdForLogs(),
                "ble_probe_exhausted",
                mapOf("msg" to "All probe attempts failed or no more candidates"),
            )
            stopConnectProbe()
            return
        }
        val (mac, rssi) = connectProbeList[index]
        connectProbeIndex = index
        connectProbeAttemptMac = mac
        connectProbeSawConnectedForAttempt = false
        connectProbeFailureHandledForAttempt = false
        logger.i(
            sessionIdForLogs(),
            "ble_probe_trying_device",
            mapOf(
                "mac" to mac,
                "rssi" to rssi,
                "msg" to "Trying device: $mac, RSSI: $rssi",
            ),
        )
        mainHandler.removeCallbacks(connectProbeTimeoutRunnable)
        mainHandler.postDelayed(connectProbeTimeoutRunnable, PROBE_CONNECT_TIMEOUT_MS)
        openGattDiscoveryConnection(mac, cleanupDelayMs = 0L)
    }

    private fun prepareGattTeardownSync() {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        pendingNotifyCharacteristics.clear()
    }

    @SuppressLint("MissingPermission")
    private fun performGattConnect(
        device: BluetoothDevice,
        addressForLogs: String,
        logBleConnectAttempt: Boolean,
        bondPairingConflictWarn: Boolean,
        forceSafeConnectPath: Boolean,
        deviceSource: String,
    ) {
        val bondState = device.bondState
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_pre_connect",
            mapOf(
                "mac" to addressForLogs,
                "deviceSource" to deviceSource,
                "bondState" to bondState,
                "bondStateName" to bondStateName(bondState),
                "bondPairingConflictWarn" to bondPairingConflictWarn,
                "forceSafeConnectPath" to forceSafeConnectPath,
            ),
        )
        when {
            bondState == BluetoothDevice.BOND_BONDED && bondPairingConflictWarn ->
                logger.w(
                    sessionIdForLogs(),
                    "ble_gatt_bonded_may_conflict",
                    mapOf(
                        "msg" to "Device is bonded, may conflict with BLE",
                        "mac" to addressForLogs,
                        "bondState" to bondState,
                    ),
                )
            bondState == BluetoothDevice.BOND_BONDED && !bondPairingConflictWarn ->
                logger.i(
                    sessionIdForLogs(),
                    "ble_gatt_bonded_force_safe",
                    mapOf(
                        "msg" to "Device is bonded; force safe connect proceeding (TRANSPORT_LE, post-scan delay)",
                        "mac" to addressForLogs,
                    ),
                )
        }
        discoveryMode = true
        if (logBleConnectAttempt) {
            logger.i(
                sessionIdForLogs(),
                "ble_connect_attempt",
                mapOf("address" to addressForLogs, "deviceSource" to deviceSource),
            )
        }
        val transportLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "TRANSPORT_LE"
        } else {
            "LEGACY_UNSPECIFIED"
        }
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_connect_begin",
            mapOf(
                "mac" to addressForLogs,
                "autoConnect" to false,
                "transport" to transportLabel,
                "apiLevel" to Build.VERSION.SDK_INT,
                "deviceSource" to deviceSource,
            ),
        )
        logger.i(
            sessionIdForLogs(),
            "ble_gatt_connection_state_sink",
            mapOf("msg" to "BluetoothGattCallback registered with connectGatt (native equivalent of connectionState.listen)"),
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
        if (scanConnectSessionActive && scanConnectAutoRetryEnabled) {
            mainHandler.removeCallbacks(scanConnectGattWatchdogRunnable)
            mainHandler.postDelayed(scanConnectGattWatchdogRunnable, SCAN_CONNECT_GATT_WATCHDOG_MS)
        }
    }

    /**
     * Anonymous probe only: MAC from prior scan accumulator (not the preferred W1 scan→connect path).
     */
    @SuppressLint("MissingPermission")
    private fun openGattDiscoveryConnection(
        normalizedMac: String,
        logBleConnectAttempt: Boolean = true,
        bondPairingConflictWarn: Boolean = true,
        forceSafeConnectPath: Boolean = false,
        cleanupDelayMs: Long = GATT_CLEANUP_BEFORE_CONNECT_MS,
    ) {
        val ad = adapter ?: return
        val device = try {
            ad.getRemoteDevice(normalizedMac)
        } catch (e: IllegalArgumentException) {
            logger.e(sessionIdForLogs(), "ble_connect_invalid_mac", mapOf("mac" to normalizedMac), e)
            onBleError("invalid MAC: ${e.message}")
            return
        }
        prepareGattTeardownSync()
        val address = normalizedMac.uppercase(Locale.US)
        if (cleanupDelayMs <= 0L) {
            performGattConnect(
                device,
                address,
                logBleConnectAttempt,
                bondPairingConflictWarn,
                forceSafeConnectPath,
                deviceSource = "remote_mac_probe",
            )
            return
        }
        mainHandler.postDelayed(
            {
                performGattConnect(
                    device,
                    address,
                    logBleConnectAttempt,
                    bondPairingConflictWarn,
                    forceSafeConnectPath,
                    deviceSource = "remote_mac_probe",
                )
            },
            cleanupDelayMs,
        )
    }

    @SuppressLint("MissingPermission")
    private fun openGattDiscoveryConnectionFromScanResult(
        device: BluetoothDevice,
        logBleConnectAttempt: Boolean = true,
        bondPairingConflictWarn: Boolean = true,
        forceSafeConnectPath: Boolean = false,
    ) {
        performGattConnect(
            device,
            device.address.uppercase(Locale.US),
            logBleConnectAttempt,
            bondPairingConflictWarn,
            forceSafeConnectPath,
            deviceSource = "scan_result",
        )
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
     * LE scan (15 s) for advertisements matching [macAddress] or [localNameHint], then
     * GATT teardown + 500 ms → [connectGatt] on the **scanned** [BluetoothDevice] (TRANSPORT_LE, autoConnect false).
     *
     * @param localNameHint `null` → use [DEFAULT_W1_BLE_LOCAL_NAME]; blank string → MAC-only match.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(macAddress: String, localNameHint: String? = null) {
        val nameFilter = when {
            localNameHint == null -> DEFAULT_W1_BLE_LOCAL_NAME
            localNameHint.isBlank() -> null
            else -> localNameHint.trim()
        }
        beginW1ScanBasedConnect(
            macAddress = macAddress,
            localNameHint = nameFilter,
            bondPairingConflictWarn = true,
            forceSafeConnectPath = false,
        )
    }

    /** Same as [connectToDevice] but skips bonded-device conflict warning (debug). */
    @SuppressLint("MissingPermission")
    fun forceBleSafeConnect(macAddress: String, localNameHint: String? = null) {
        val nameFilter = when {
            localNameHint == null -> DEFAULT_W1_BLE_LOCAL_NAME
            localNameHint.isBlank() -> null
            else -> localNameHint.trim()
        }
        beginW1ScanBasedConnect(
            macAddress = macAddress,
            localNameHint = nameFilter,
            bondPairingConflictWarn = false,
            forceSafeConnectPath = true,
        )
    }

    @SuppressLint("MissingPermission")
    fun startScanIfPermitted() {
        cancelScanConnectSession()
        emitBleScanUi(mapOf("phase" to "idle", "detail" to "Background discovery scan (connect session cancelled)"))
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
        scanAccumulator.clear()
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
        stopConnectProbe()
        cancelScanConnectSession()
        emitBleScanUi(mapOf("phase" to "idle", "detail" to "Disconnected"))
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
