package com.example.falcon_one_demo.w1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-oriented BLE discovery: **no strict name filtering**, full advertisement logging,
 * optional service / manufacturer heuristics, manual MAC selection, scan → delayed →
 * [BluetoothDevice.connectGatt], and GATT connect retries.
 *
 * Permissions (caller must hold): [android.Manifest.permission.BLUETOOTH_SCAN],
 * [android.Manifest.permission.BLUETOOTH_CONNECT], and on older APIs the legacy Bluetooth permissions.
 */
class BleRobustDiscoveryScanner(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "BleRobustDiscovery"

    private var scanner: BluetoothLeScanner? = null
    private val scanning = AtomicBoolean(false)
    private val aggregates = ConcurrentHashMap<String, DeviceAggregate>()
    private val scanGeneration = AtomicInteger(0)

    /** Normalized upper-case MAC chosen for [connectSelected] / [connectToAddress]. */
    @Volatile
    var selectedMacAddress: String? = null
        private set

    @Volatile
    private var pendingGatt: BluetoothGatt? = null

    /** True only after [BluetoothProfile.STATE_CONNECTED] with [BluetoothGatt.GATT_SUCCESS]. */
    private var gattFullyConnected: Boolean = false

    private var connectAttemptNumber = 0
    private var connectRetryRunnable: Runnable? = null
    private var pendingConnectDevice: BluetoothDevice? = null

    private var scanStopRunnable: Runnable? = null
    private var sessionListener: SessionListener? = null

    /** Tunables — adjust for your product. */
    data class Config(
        /** Total time to listen per [startDiscoveryScan] before auto [stopScan] (ms). */
        val scanDurationMs: Long = 12_000L,
        /** Idle after [stopScan] before [connectGatt] (ms). */
        val preConnectDelayMs: Long = 750L,
        val maxGattConnectAttempts: Int = 3,
        /** Delay between failed GATT attempts (ms). */
        val gattRetryDelayMs: Long = 600L,
        /**
         * RSSI band considered “strong / nearby” for ranking (inclusive).
         * Typical indoor: closer to -40 is stronger; -70 is still usable.
         */
        val rssiStrongMin: Int = -70,
        val rssiStrongMax: Int = -40,
        /** Optional: only boost ranking if advertisement contains this service UUID. */
        val preferredServiceUuids: Set<UUID> = emptySet(),
        /** Optional: boost if manufacturer company id matches (Bluetooth SIG company identifier). */
        val preferredManufacturerId: Int? = null,
        /** Optional: prefix match on manufacturer payload after the 2-byte company id. */
        val preferredManufacturerDataPrefix: ByteArray? = null,
    )

    private var config = Config()

    data class BleCandidate(
        val address: String,
        val bestName: String?,
        val bestRssi: Int,
        val advertisementCount: Int,
        val serviceUuids: Set<UUID>,
        val manufacturerSummary: String,
        /** Higher = better for [rankAutoPick]. */
        val heuristicScore: Int,
    )

    interface SessionListener {
        fun onScanResultLine(humanReadableLine: String, structuredFields: Map<String, String>)
        fun onScanFinished(candidates: List<BleCandidate>)
        fun onSelectedDevice(address: String, device: BluetoothDevice)
        fun onGattConnecting(address: String, attempt: Int, maxAttempts: Int)
        fun onGattConnected(gatt: BluetoothGatt)
        fun onGattConnectionFailed(address: String, status: Int, willRetry: Boolean)
        fun onGattDisconnected(gatt: BluetoothGatt, status: Int)
    }

    fun setSessionListener(listener: SessionListener?) {
        sessionListener = listener
    }

    fun setConfig(config: Config) {
        this.config = config
    }

    fun clearAggregates() {
        aggregates.clear()
    }

    /**
     * Persist manual test selection (upper-case MAC with colons).
     */
    fun selectDeviceByAddress(macAddress: String) {
        selectedMacAddress = normalizeMac(macAddress)
        Log.i(tag, "Manual selection stored → MAC: ${selectedMacAddress}")
    }

    /**
     * Rank seen devices: repeated sightings, RSSI band, optional UUID / manufacturer match.
     */
    fun rankAutoPick(): BleCandidate? {
        val list = aggregates.values.map { it.toCandidate(config) }
        if (list.isEmpty()) return null
        return list.maxByOrNull { it.heuristicScore }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    @SuppressLint("MissingPermission")
    fun startDiscoveryScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(tag, "Bluetooth off or unavailable — cannot scan")
            return
        }
        stopScanInternal(cancelPendingStop = true)
        clearAggregates()
        scanGeneration.incrementAndGet()
        scanning.set(true)
        scanner = adapter.bluetoothLeScanner
        val leScanner = scanner
        if (leScanner == null) {
            Log.e(tag, "BluetoothLeScanner unavailable")
            scanning.set(false)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner.startScan(null, settings, scanCallback)
        Log.i(tag, "Scan started (unfiltered), duration=${config.scanDurationMs} ms")

        scanStopRunnable = Runnable {
            stopScanInternal(cancelPendingStop = false)
            val ranked = aggregates.values.map { it.toCandidate(config) }.sortedByDescending { it.heuristicScore }
            sessionListener?.onScanFinished(ranked)
            Log.i(tag, "Scan finished — unique devices: ${ranked.size}")
            ranked.take(10).forEachIndexed { i, c ->
                Log.i(
                    tag,
                    "Candidate #$i score=${c.heuristicScore} → " +
                        "Name: ${c.bestName ?: "(no name)"} | Address: ${c.address} | RSSI: ${c.bestRssi} | " +
                        "Ads: ${c.advertisementCount} | Mfg: ${c.manufacturerSummary}",
                )
            }
        }
        mainHandler.postDelayed(scanStopRunnable!!, config.scanDurationMs)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        stopScanInternal(cancelPendingStop = true)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
    @SuppressLint("MissingPermission")
    private fun stopScanInternal(cancelPendingStop: Boolean) {
        if (cancelPendingStop) {
            scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
            scanStopRunnable = null
        }
        if (!scanning.get()) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning.set(false)
        Log.i(tag, "Scan stopped")
    }

    /**
     * After scan / selection: connect to [selectedMacAddress] (must call [selectDeviceByAddress] first).
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun connectSelected() {
        val mac = selectedMacAddress
        if (mac.isNullOrEmpty()) {
            Log.e(tag, "connectSelected: no MAC — call selectDeviceByAddress() or autoSelectStrongest()")
            return
        }
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Invalid MAC: $mac", e)
            return
        }
        connectToDevice(device)
    }

    /**
     * Pick best candidate from current [aggregates] and store its MAC.
     */
    fun autoSelectStrongest() {
        val pick = rankAutoPick()
        if (pick == null) {
            Log.w(tag, "autoSelectStrongest: no devices seen yet")
            return
        }
        selectedMacAddress = pick.address
        Log.i(
            tag,
            "Auto-selected → Name: ${pick.bestName ?: "(no name)"} | Address: ${pick.address} | " +
                "RSSI: ${pick.bestRssi} | score=${pick.heuristicScore}",
        )
    }

    /**
     * One-shot: scan for [config.scanDurationMs], stop, auto-rank, store MAC, then connect.
     */
    @RequiresPermission(allOf = [android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT])
    fun startScanThenAutoConnect() {
        startDiscoveryScan()
        val gen = scanGeneration.get()
        mainHandler.postDelayed({
            if (scanGeneration.get() != gen) return@postDelayed
            autoSelectStrongest()
            connectSelected()
        }, config.scanDurationMs + 50L)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToAddress(macAddress: String) {
        selectDeviceByAddress(macAddress)
        connectSelected()
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        cancelConnectRetry()
        closeGattQuietly()
        gattFullyConnected = false
        pendingConnectDevice = device
        connectAttemptNumber = 0
        sessionListener?.onSelectedDevice(device.address.uppercase(Locale.US), device)
        scheduleGattConnectAttempt(device, attempt = 1)
    }

    fun release() {
        stopScan()
        cancelConnectRetry()
        closeGattQuietly()
        sessionListener = null
    }

    // --- internals ---

    private fun cancelConnectRetry() {
        connectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        connectRetryRunnable = null
        pendingConnectDevice = null
        connectAttemptNumber = 0
    }

    private fun scheduleGattConnectAttempt(device: BluetoothDevice, attempt: Int) {
        connectAttemptNumber = attempt
        connectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val delayMs = if (attempt == 1) config.preConnectDelayMs else 0L
        val r = Runnable {
            connectAttemptNumber = attempt
            sessionListener?.onGattConnecting(device.address.uppercase(Locale.US), attempt, config.maxGattConnectAttempts)
            Log.i(
                tag,
                "GATT connect (attempt $attempt/${config.maxGattConnectAttempts}) " +
                    "delayMs=$delayMs → ${device.address}",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
        }
        connectRetryRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun closeGattQuietly() {
        try {
            pendingGatt?.disconnect()
            pendingGatt?.close()
        } catch (_: Exception) {
        }
        pendingGatt = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordAndLog(result, delivery = "single")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) {
                recordAndLog(r, delivery = "batch")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "onScanFailed code=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordAndLog(result: ScanResult, delivery: String = "single") {
        val device = result.device
        val addr = device.address.uppercase(Locale.US)
        val nameDirect = device.name?.trim()?.takeIf { it.isNotEmpty() }
        val record = result.scanRecord
        val nameFromAdv = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedName = nameDirect ?: nameFromAdv
        val rssi = result.rssi

        val mfgHex = formatManufacturerData(record)
        val svcUuids = extractServiceUuids(record)

        val agg = aggregates.getOrPut(addr) { DeviceAggregate(address = addr) }
        agg.recordSighting(resolvedName, rssi, mfgHex, svcUuids)

        val mfgShort = if (mfgHex.length > 120) mfgHex.take(120) + "…" else mfgHex
        val svcStr = svcUuids.joinToString(", ") { shortUuid(it) }

        val humanLine =
            "Device Found → Name: ${resolvedName ?: "(no name)"} | Address: $addr | RSSI: $rssi"
        Log.i(tag, humanLine)
        Log.d(
            tag,
            "  … services=[$svcStr] | manufacturer=[$mfgShort] | delivery=$delivery",
        )

        val structured = linkedMapOf(
            "name" to (resolvedName ?: ""),
            "address" to addr,
            "rssi" to rssi.toString(),
            "manufacturer" to mfgHex,
            "services" to svcStr,
        )
        sessionListener?.onScanResultLine(humanLine, structured)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address.uppercase(Locale.US)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
                        connectRetryRunnable = null
                        gattFullyConnected = true
                        pendingGatt = gatt
                        Log.i(tag, "GATT connected → $addr")
                        sessionListener?.onGattConnected(gatt)
                    } else {
                        Log.w(tag, "STATE_CONNECTED but status=$status → $addr")
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                        queueGattConnectRetry(gatt.device, status)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(tag, "GATT disconnected → $addr status=$status (connected=$gattFullyConnected)")
                    sessionListener?.onGattDisconnected(gatt, status)
                    if (pendingGatt === gatt) {
                        pendingGatt = null
                    }
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (!gattFullyConnected) {
                        queueGattConnectRetry(gatt.device, status)
                    }
                }
            }
        }
    }

    private fun queueGattConnectRetry(device: BluetoothDevice, status: Int) {
        val next = connectAttemptNumber + 1
        if (next > config.maxGattConnectAttempts) {
            Log.e(tag, "GATT connect failed after ${config.maxGattConnectAttempts} attempts (last status=$status)")
            sessionListener?.onGattConnectionFailed(device.address.uppercase(Locale.US), status, willRetry = false)
            connectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
            connectRetryRunnable = null
            return
        }
        Log.w(tag, "GATT connect retry in ${config.gattRetryDelayMs} ms (next attempt $next)")
        sessionListener?.onGattConnectionFailed(device.address.uppercase(Locale.US), status, willRetry = true)
        connectRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { scheduleGattConnectAttempt(device, attempt = next) }
        connectRetryRunnable = r
        mainHandler.postDelayed(r, config.gattRetryDelayMs)
    }

    private class DeviceAggregate(val address: String) {
        var bestRssi: Int = Int.MIN_VALUE
        var bestName: String? = null
        var advertisementCount: Int = 0
        val serviceUuids = mutableSetOf<UUID>()
        var manufacturerSummary: String = ""

        @Synchronized
        fun recordSighting(name: String?, rssi: Int, mfg: String, services: Set<UUID>) {
            advertisementCount++
            if (rssi > bestRssi) {
                bestRssi = rssi
                if (!name.isNullOrEmpty()) bestName = name
            } else if (bestName.isNullOrEmpty() && !name.isNullOrEmpty()) {
                bestName = name
            }
            serviceUuids.addAll(services)
            if (mfg.isNotEmpty()) manufacturerSummary = mfg
        }

        fun toCandidate(cfg: Config): BleCandidate {
            val rssiScore = when (bestRssi) {
                in cfg.rssiStrongMin..cfg.rssiStrongMax -> 40
                in -85..cfg.rssiStrongMin -> 20
                else -> 5
            }
            val repeatScore = advertisementCount.coerceAtMost(50) * 3
            var bonus = 0
            for (u in cfg.preferredServiceUuids) {
                if (serviceUuids.contains(u)) bonus += 80
            }
            cfg.preferredManufacturerId?.let { mid ->
                if (manufacturerSummary.startsWith("id=0x${"%04X".format(mid)}")) {
                    bonus += 60
                }
            }
            val prefix = cfg.preferredManufacturerDataPrefix
            if (prefix != null && prefix.isNotEmpty()) {
                val raw = parseManufacturerPayloadHex(manufacturerSummary) ?: ""
                val pfx = prefix.joinToString("") { "%02X".format(it) }
                if (raw.contains(pfx, ignoreCase = true)) bonus += 50
            }
            val score = rssiScore + repeatScore + bonus
            return BleCandidate(
                address = address,
                bestName = bestName,
                bestRssi = bestRssi,
                advertisementCount = advertisementCount,
                serviceUuids = serviceUuids.toSet(),
                manufacturerSummary = manufacturerSummary,
                heuristicScore = score,
            )
        }
    }

    companion object {
        fun normalizeMac(mac: String): String =
            mac.trim().uppercase(Locale.US)

        private fun shortUuid(u: UUID): String = u.toString().take(8) + "…"

        private fun formatManufacturerData(record: ScanRecord?): String {
            if (record == null) return ""
            val sparse = record.manufacturerSpecificData ?: return ""
            val sb = StringBuilder()
            for (i in 0 until sparse.size()) {
                val key = sparse.keyAt(i)
                val bytes = sparse.valueAt(i) ?: continue
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append("id=0x%04X".format(key and 0xFFFF))
                sb.append(" data=")
                bytes.take(32).forEach { b -> sb.append("%02X".format(b)) }
                if (bytes.size > 32) sb.append("…(${bytes.size}b)")
            }
            return sb.toString()
        }

        private fun parseManufacturerPayloadHex(summary: String): String? {
            val idx = summary.indexOf("data=")
            if (idx < 0) return null
            return summary.substring(idx + 5).filter { it.isLetterOrDigit() }
        }

        private fun extractServiceUuids(record: ScanRecord?): Set<UUID> {
            if (record == null) return emptySet()
            val out = LinkedHashSet<UUID>()
            record.serviceUuids?.forEach { out.add(it.uuid) }
            return out
        }
    }
}
