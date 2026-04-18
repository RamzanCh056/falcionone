package com.example.falcon_one_demo.w1

import java.util.UUID

/**
 * Replace with vendor W1 documentation. Placeholder UUIDs compile but will not match hardware
 * until updated.
 */
data class W1DeviceUuids(
    val service: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
    val recordingStatusCharacteristic: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
)
