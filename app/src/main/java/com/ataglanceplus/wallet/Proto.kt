package com.ataglanceplus.wallet

import java.io.ByteArrayOutputStream

/**
 * Just enough of the protocol buffer wire format to read Google Wallet's sync response and write
 * its request, without generated classes. Fields keep their order; a message is read lazily from
 * its bytes. Field numbers come from SmartspacerPlugins' Wallet .proto files (see NOTICE).
 */
class Proto(bytes: ByteArray) {

    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (i < bytes.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> (0 until 8).fold(0L) { acc, k -> acc or ((bytes[i + k].toLong() and 0xFF) shl (8 * k)) }.also { i += 8 }
                2 -> {
                    val length = varint().toInt()
                    bytes.copyOfRange(i, i + length).also { i += length }
                }
                5 -> (0 until 4).fold(0L) { acc, k -> acc or ((bytes[i + k].toLong() and 0xFF) shl (8 * k)) }.also { i += 4 }
                else -> throw IllegalArgumentException("Unknown wire type in field $number")
            }
            fields.getOrPut(number) { mutableListOf() } += value
        }
    }

    fun has(n: Int): Boolean = fields.containsKey(n)
    fun bytes(n: Int): ByteArray? = fields[n]?.firstOrNull() as? ByteArray
    fun str(n: Int): String? = bytes(n)?.let { String(it, Charsets.UTF_8) }?.ifEmpty { null }
    fun long(n: Int): Long = fields[n]?.firstOrNull() as? Long ?: 0L
    fun int(n: Int): Int = long(n).toInt()
    fun bool(n: Int): Boolean = long(n) != 0L
    fun float(n: Int): Float = Float.fromBits(long(n).toInt())
    fun double(n: Int): Double = Double.fromBits(long(n))
    fun msg(n: Int): Proto? = bytes(n)?.let(::Proto)
    fun msgs(n: Int): List<Proto> = fields[n].orEmpty().mapNotNull { (it as? ByteArray)?.let(::Proto) }

    /** The first of [numbers] that is set, with its number (a oneof). */
    fun oneOf(vararg numbers: Int): Pair<Int, ByteArray>? {
        for (n in numbers) bytes(n)?.let { return n to it }
        return null
    }
}

/** Writes protocol buffer fields, for the sync request. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun bytes(n: Int, value: ByteArray) = apply {
        varint((n.toLong() shl 3) or 2)
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(n: Int, value: String) = bytes(n, value.toByteArray(Charsets.UTF_8))

    fun long(n: Int, value: Long) = apply {
        varint(n.toLong() shl 3)
        varint(value)
    }

    fun message(n: Int, build: ProtoWriter.() -> Unit) = bytes(n, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}
