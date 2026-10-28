/*
 * Derived from Shizuku (https://github.com/RikkaApps/Shizuku), manager module,
 * package moe.shizuku.manager.adb. Copyright Rikka contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.ataglanceplus.adb

import com.ataglanceplus.adb.AdbProtocol.A_AUTH
import com.ataglanceplus.adb.AdbProtocol.A_CLSE
import com.ataglanceplus.adb.AdbProtocol.A_CNXN
import com.ataglanceplus.adb.AdbProtocol.A_OKAY
import com.ataglanceplus.adb.AdbProtocol.A_OPEN
import com.ataglanceplus.adb.AdbProtocol.A_STLS
import com.ataglanceplus.adb.AdbProtocol.A_SYNC
import com.ataglanceplus.adb.AdbProtocol.A_WRTE
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB protocol message: a 24-byte header plus an optional payload. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
    val magic: Int,
    val data: ByteArray?
) {

    constructor(command: Int, arg0: Int, arg1: Int, data: String) :
        this(command, arg0, arg1, "$data\u0000".toByteArray())

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command,
        arg0,
        arg1,
        data?.size ?: 0,
        checksum(data),
        (command.toLong() xor 0xFFFFFFFF).toInt(),
        data
    )

    fun validate(): Boolean {
        if (command != magic xor -0x1) return false
        if (dataLength != 0 && checksum(data) != dataChecksum) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) throw IllegalArgumentException("bad message ${toStringShort()}")
    }

    fun toByteArray(): ByteArray {
        val length = HEADER_LENGTH + (data?.size ?: 0)
        return ByteBuffer.allocate(length).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(dataChecksum)
            putInt(magic)
            if (data != null) put(data)
        }.array()
    }

    override fun toString(): String = "AdbMessage(${toStringShort()})"

    fun toStringShort(): String {
        val commandString = when (command) {
            A_SYNC -> "A_SYNC"
            A_CNXN -> "A_CNXN"
            A_AUTH -> "A_AUTH"
            A_OPEN -> "A_OPEN"
            A_OKAY -> "A_OKAY"
            A_CLSE -> "A_CLSE"
            A_WRTE -> "A_WRTE"
            A_STLS -> "A_STLS"
            else -> command.toString()
        }
        return "command=$commandString, arg0=$arg0, arg1=$arg1, length=$dataLength"
    }

    companion object {
        const val HEADER_LENGTH = 24

        /** adb's "checksum" is a plain byte sum. */
        private fun checksum(data: ByteArray?): Int {
            if (data == null) return 0
            var res = 0
            for (b in data) res += b.toInt() and 0xFF
            return res
        }
    }
}
