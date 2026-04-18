package com.example.falcon_one_demo.w1

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

interface W1WifiFileClient {
    suspend fun listRecordings(baseUrl: String): Result<List<RemoteRecording>>

    suspend fun downloadRecording(
        baseUrl: String,
        recordingId: String,
        destination: File,
        expectedSize: Long?,
        onProgress: (loaded: Long, total: Long) -> Unit,
    ): Result<Unit>
}

class OkHttpW1WifiFileClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) : W1WifiFileClient {

    override suspend fun listRecordings(baseUrl: String): Result<List<RemoteRecording>> = runCatching {
        val root = baseUrl.trimEnd('/')
        val req = Request.Builder().url("$root/recordings").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("list HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: error("empty body")
            parseRecordings(body)
        }
    }

    override suspend fun downloadRecording(
        baseUrl: String,
        recordingId: String,
        destination: File,
        expectedSize: Long?,
        onProgress: (loaded: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val root = baseUrl.trimEnd('/')
        val req = Request.Builder().url("$root/recordings/$recordingId/content").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download HTTP ${resp.code}")
            val body = resp.body ?: error("no body")
            val total = body.contentLength().takeIf { it >= 0 } ?: (expectedSize ?: -1L)
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var loaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        loaded += n
                        onProgress(loaded, if (total > 0) total else loaded)
                    }
                }
            }
        }
    }

    companion object {
        fun parseRecordings(json: String): List<RemoteRecording> {
            val arr = JSONArray(json)
            val out = ArrayList<RemoteRecording>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    RemoteRecording(
                        id = o.getString("id"),
                        name = o.optString("name", o.getString("id")),
                        sizeBytes = o.getLong("sizeBytes"),
                        sha256Hex = if (o.has("sha256") && !o.isNull("sha256")) o.getString("sha256") else null,
                        completedAtEpochMs = o.getLong("completedAtEpochMs"),
                    ),
                )
            }
            return out
        }
    }
}

/** Deterministic in-memory server for tests / offline demo. */
class MockW1WifiFileClient(
    var recordings: List<RemoteRecording>,
    var contentById: Map<String, ByteArray>,
) : W1WifiFileClient {
    override suspend fun listRecordings(baseUrl: String): Result<List<RemoteRecording>> =
        Result.success(recordings)

    override suspend fun downloadRecording(
        baseUrl: String,
        recordingId: String,
        destination: File,
        expectedSize: Long?,
        onProgress: (loaded: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val bytes = contentById[recordingId] ?: error("unknown id $recordingId")
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }
}
