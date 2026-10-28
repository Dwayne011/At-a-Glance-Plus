/*
 * Derived from Shizuku (https://github.com/RikkaApps/Shizuku), manager module,
 * package moe.shizuku.manager.adb. Copyright Rikka contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.ataglanceplus.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Finds the port adbd listens on, by watching the multicast DNS services it advertises:
 * one while the "Pair device with pairing code" dialog is open, another for normal connections.
 */
object AdbMdns {

    const val TLS_CONNECT = "_adb-tls-connect._tcp"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    private const val TAG = "AdbMdns"

    /** Waits up to [timeoutMs] for this device's adbd service of [serviceType]; returns its port. */
    suspend fun discover(context: Context, serviceType: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) { ports(context, serviceType).first { it > 0 } }

    /** Emits a port whenever a matching local service is resolved; -1 when it goes away. */
    fun ports(context: Context, serviceType: String) = callbackFlow {
        val nsdManager = context.getSystemService(NsdManager::class.java)
        val executor = Executors.newSingleThreadExecutor()
        var serviceName: String? = null

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                close()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.v(TAG, "found ${serviceInfo.serviceName}")
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        if (isLocal(resolved) && isPortInUse(resolved.port)) {
                            serviceName = resolved.serviceName
                            trySend(resolved.port)
                        }
                        nsdManager.unregisterServiceInfoCallback(this)
                    }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == serviceName) trySend(-1)
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            executor.shutdown()
        }
    }

    /** The advertised host must be one of this device's own addresses. */
    private fun isLocal(info: NsdServiceInfo): Boolean {
        val local = NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .map { it.hostAddress }
            .toSet()
        return info.hostAddresses.any { it.hostAddress in local }
    }

    private fun isPortInUse(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }
}
