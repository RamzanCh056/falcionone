package com.example.falcon_one_demo.w1

import android.net.Network
import android.os.Build
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object W1OkHttpFactories {
    fun clientForNetwork(
        network: Network?,
        connectSec: Long = 20,
        readSec: Long = 120,
    ): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (network != null && Build.VERSION.SDK_INT >= 23) {
            b.socketFactory(network.socketFactory)
        }
        return b.build()
    }
}
