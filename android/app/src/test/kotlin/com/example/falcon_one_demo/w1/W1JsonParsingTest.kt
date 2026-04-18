package com.example.falcon_one_demo.w1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class W1JsonParsingTest {
    @Test
    fun parseRecordingsArray() {
        val json =
            """[{"id":"r1","name":"clip.wav","sizeBytes":1200,"sha256":null,"completedAtEpochMs":99}]"""
        val list = OkHttpW1WifiFileClient.parseRecordings(json)
        assertEquals(1, list.size)
        assertEquals("r1", list[0].id)
        assertEquals(1200L, list[0].sizeBytes)
        assertNull(list[0].sha256Hex)
        assertEquals(99L, list[0].completedAtEpochMs)
    }
}
