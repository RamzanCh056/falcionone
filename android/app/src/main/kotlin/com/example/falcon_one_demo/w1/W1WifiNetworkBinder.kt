package com.example.falcon_one_demo.w1

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Binds the process default network to a Wi‑Fi [Network] suitable for talking to the recorder.
 *
 * **Production:** narrow with capabilities / SSID when W1 documents hotspot behaviour.
 */
class W1WifiNetworkBinder(
    context: Context,
    private val logger: W1Logger,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var lastBound: Network? = null

    /**
     * Waits for an active Wi‑Fi network, then binds the process to it (API 23+).
     * Always call [unbind] after transfer completes or on error.
     */
    suspend fun awaitWifiAndBind(sessionId: String, timeoutMs: Long = 45_000L): Network? {
        val network = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!cont.isActive) return
                        try {
                            cm.unregisterNetworkCallback(this)
                        } catch (_: Exception) {
                        }
                        if (Build.VERSION.SDK_INT >= 23) {
                            val ok = cm.bindProcessToNetwork(network)
                            logger.i(
                                sessionId,
                                "wifi_bind",
                                mapOf("network" to network.toString(), "bindOk" to ok),
                            )
                        } else {
                            logger.w(
                                sessionId,
                                "wifi_bind_skipped_api",
                                mapOf("sdk" to Build.VERSION.SDK_INT),
                            )
                        }
                        lastBound = network
                        cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (!cont.isActive) return
                        try {
                            cm.unregisterNetworkCallback(this)
                        } catch (_: Exception) {
                        }
                        cont.resume(null)
                    }
                }

                try {
                    cm.requestNetwork(request, callback)
                } catch (e: Exception) {
                    logger.e(sessionId, "wifi_request_failed", emptyMap(), e)
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    try {
                        cm.unregisterNetworkCallback(callback)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (network == null) {
            logger.w(sessionId, "wifi_bind_timeout_or_null", mapOf("timeoutMs" to timeoutMs))
        }
        return network
    }

    fun unbind(sessionId: String) {
        if (Build.VERSION.SDK_INT >= 23) {
            cm.bindProcessToNetwork(null)
        }
        logger.i(sessionId, "wifi_unbind", mapOf("hadNetwork" to (lastBound != null)))
        lastBound = null
    }
}
