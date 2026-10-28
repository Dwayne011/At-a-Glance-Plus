/*
 * Derived from Shizuku (https://github.com/RikkaApps/Shizuku), manager module,
 * package moe.shizuku.manager.adb. Copyright Rikka contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.ataglanceplus.adb

import android.util.Log
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairingClient"

private const val CURRENT_KEY_HEADER_VERSION = 1.toByte()
private const val MIN_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_PEER_INFO_SIZE = 8192
private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
private const val EXPORTED_KEY_SIZE = 64
private const val PAIRING_PACKET_HEADER_SIZE = 6

private const val TYPE_SPAKE2_MSG = 0.toByte()
private const val TYPE_PEER_INFO = 1.toByte()
private const val PEER_TYPE_RSA_PUB_KEY = 0.toByte()

/** Bridge to the native SPAKE2 + AES-GCM pairing state (adb_pairing.cpp). */
internal class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(input: ByteArray) = nativeEncrypt(nativePtr, input)
    fun decrypt(input: ByteArray) = nativeDecrypt(nativePtr, input)
    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            System.loadLibrary("adbpairing")
        }

        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

/**
 * Runs adb's pairing protocol against the pairing port shown in Developer options:
 * TLS handshake, SPAKE2 with (pairing code + exported TLS keying material) as the password,
 * then an encrypted exchange of public keys. On success adbd trusts our certificate.
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val key: AdbKey
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private var pairingContext: PairingContext? = null

    fun start(): Boolean {
        setupTlsConnection()
        if (!exchangeMessages()) return false
        return exchangePeerInfo()
    }

    private fun setupTlsConnection() {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 10_000)
        socket.soTimeout = 30_000

        val sslSocket = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()
        Log.d(TAG, "TLS handshake succeeded")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val codeBytes = pairingCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
        val password = codeBytes + keyMaterial

        pairingContext = PairingContext.create(password) ?: adbError("Unable to create pairing context")
    }

    private fun writePacket(type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(CURRENT_KEY_HEADER_VERSION)
            .put(type)
            .putInt(payload.size)
        outputStream.write(header.array())
        outputStream.write(payload)
        outputStream.flush()
    }

    /** Returns the payload of the next packet, or null if the header is unacceptable. */
    private fun readPacket(expectedType: Byte): ByteArray? {
        val bytes = ByteArray(PAIRING_PACKET_HEADER_SIZE)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val payloadSize = buffer.int
        if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) {
            Log.e(TAG, "header version mismatch: $version")
            return null
        }
        if (type != expectedType) {
            Log.e(TAG, "unexpected packet type $type")
            return null
        }
        if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD_SIZE) {
            Log.e(TAG, "unsafe payload size $payloadSize")
            return null
        }
        return ByteArray(payloadSize).also { inputStream.readFully(it) }
    }

    private fun exchangeMessages(): Boolean {
        val context = pairingContext ?: return false
        writePacket(TYPE_SPAKE2_MSG, context.msg)
        val theirMessage = readPacket(TYPE_SPAKE2_MSG) ?: return false
        return context.initCipher(theirMessage)
    }

    private fun exchangePeerInfo(): Boolean {
        val context = pairingContext ?: return false
        val info = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        info.put(PEER_TYPE_RSA_PUB_KEY)
        val publicKey = key.adbPublicKey
        info.put(publicKey, 0, publicKey.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))

        val encrypted = context.encrypt(info.array()) ?: return false
        writePacket(TYPE_PEER_INFO, encrypted)

        val theirEncrypted = readPacket(TYPE_PEER_INFO) ?: return false
        val decrypted = context.decrypt(theirEncrypted) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != MAX_PEER_INFO_SIZE) {
            Log.e(TAG, "unexpected peer info size ${decrypted.size}")
            return false
        }
        return true
    }

    override fun close() {
        if (::inputStream.isInitialized) runCatching { inputStream.close() }
        if (::outputStream.isInitialized) runCatching { outputStream.close() }
        if (::socket.isInitialized) runCatching { socket.close() }
        pairingContext?.destroy()
        pairingContext = null
    }
}
