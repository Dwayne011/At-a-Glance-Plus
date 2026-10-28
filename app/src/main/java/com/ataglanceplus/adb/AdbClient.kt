/*
 * Derived from Shizuku (https://github.com/RikkaApps/Shizuku), manager module,
 * package moe.shizuku.manager.adb. Copyright Rikka contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.ataglanceplus.adb

import android.util.Log
import com.ataglanceplus.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.ataglanceplus.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.ataglanceplus.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.ataglanceplus.adb.AdbProtocol.A_AUTH
import com.ataglanceplus.adb.AdbProtocol.A_CLSE
import com.ataglanceplus.adb.AdbProtocol.A_CNXN
import com.ataglanceplus.adb.AdbProtocol.A_MAXDATA
import com.ataglanceplus.adb.AdbProtocol.A_OKAY
import com.ataglanceplus.adb.AdbProtocol.A_OPEN
import com.ataglanceplus.adb.AdbProtocol.A_STLS
import com.ataglanceplus.adb.AdbProtocol.A_STLS_VERSION
import com.ataglanceplus.adb.AdbProtocol.A_VERSION
import com.ataglanceplus.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

/** A minimal ADB client: connect (TLS on Android 11+), then run shell commands. */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect(timeoutMs: Int = 10_000) {
        socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = 30_000
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            write(A_STLS, A_STLS_VERSION, 0)
            tlsSocket = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "TLS handshake succeeded")
            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true
            message = read()
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) adbError("expected ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }
        if (message.command != A_CNXN) adbError("expected A_CNXN, got ${message.toStringShort()}")
    }

    /** Runs a shell command to completion and returns everything it printed. */
    fun shell(command: String): String {
        val out = StringBuilder()
        shellCommand(command) { out.append(String(it)) }
        return out.toString()
    }

    /** Each stream gets its own id, so late messages for an earlier stream cannot be mistaken for this one. */
    private var nextLocalId = 1

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = nextLocalId++
        write(A_OPEN, localId, 0, "shell:$command")
        while (true) {
            val message = read()
            // arg1 is our id on every stream message; anything else belongs to an earlier stream.
            if (message.arg1 != localId) {
                Log.d(TAG, "ignoring message for stream ${message.arg1}: ${message.toStringShort()}")
                continue
            }
            when (message.command) {
                // Stream opened, or a flow-control acknowledgement: nothing to do.
                A_OKAY -> Unit
                A_WRTE -> {
                    if (message.dataLength > 0) listener?.invoke(message.data!!)
                    write(A_OKAY, localId, message.arg0)
                }
                A_CLSE -> {
                    write(A_CLSE, localId, message.arg0)
                    return
                }
                else -> adbError("unexpected ${message.toStringShort()} on stream $localId")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, AdbMessage.HEADER_LENGTH)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) ByteArray(dataLength).also { inputStream.readFully(it) } else null
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        if (useTls) {
            runCatching { tlsInputStream.close() }
            runCatching { tlsOutputStream.close() }
            runCatching { tlsSocket.close() }
        }
        if (::socket.isInitialized) {
            runCatching { plainInputStream.close() }
            runCatching { plainOutputStream.close() }
            runCatching { socket.close() }
        }
    }
}
