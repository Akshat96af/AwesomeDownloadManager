package ir.amirab.downloader.downloaditem.youtube

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Writes the protobuf wire format: just varints and length-delimited fields, all SABR requests need. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long?) = apply {
        if (value == null) return@apply
        writeVarint((field.toLong() shl 3) or 0)
        writeVarint(value)
    }

    fun int(field: Int, value: Int?) = varint(field, value?.toLong())
    fun bool(field: Int, value: Boolean?) = varint(field, value?.let { if (it) 1L else 0L })

    fun bytes(field: Int, value: ByteArray?) = apply {
        if (value == null) return@apply
        writeVarint((field.toLong() shl 3) or 2)
        writeVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String?) = bytes(field, value?.encodeToByteArray())
    fun message(field: Int, value: ProtoWriter?) = bytes(field, value?.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** A decoded protobuf message: each field number maps to its values (Long for varints, ByteArray otherwise). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {
    fun long(field: Int): Long? = fields[field]?.lastOrNull() as? Long
    fun int(field: Int): Int? = long(field)?.toInt()
    fun bool(field: Int): Boolean = (long(field) ?: 0L) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.decodeToString()
    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)

    /** Repeated integers, packed or not. */
    fun ints(field: Int): List<Int> = fields[field].orEmpty().flatMap { value ->
        when (value) {
            is Long -> listOf(value.toInt())
            is ByteArray -> buildList {
                var pos = 0
                while (pos < value.size) {
                    val (v, next) = readVarint(value, pos)
                    add(v.toInt())
                    pos = next
                }
            }

            else -> emptyList()
        }
    }

    companion object {
        fun parse(bytes: ByteArray): ProtoMessage {
            val fields = HashMap<Int, MutableList<Any>>()
            var pos = 0
            while (pos < bytes.size) {
                val (key, afterKey) = readVarint(bytes, pos)
                val field = (key ushr 3).toInt()
                pos = afterKey
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint(bytes, pos).also { pos = it.second }.first
                    1 -> bytes.copyOfRange(pos, pos + 8).also { pos += 8 }
                    2 -> {
                        val (length, start) = readVarint(bytes, pos)
                        bytes.copyOfRange(start, start + length.toInt()).also { pos = start + length.toInt() }
                    }

                    5 -> bytes.copyOfRange(pos, pos + 4).also { pos += 4 }
                    else -> throw IllegalArgumentException("unsupported protobuf wire type in field $field")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }

        private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var pos = start
            while (true) {
                if (pos >= bytes.size) throw EOFException("truncated protobuf varint")
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result to pos
                shift += 7
            }
        }
    }
}

/**
 * Reads one UMP part (YouTube's framing for SABR responses): a type, a size, then the payload.
 * Both numbers use UMP's own varint, whose first byte says how many bytes follow. Returns null at end of stream.
 */
fun readUmpPart(input: InputStream): Pair<Int, ByteArray>? {
    val type = readUmpVarint(input, allowEnd = true) ?: return null
    val size = readUmpVarint(input, allowEnd = false)!!
    return type.toInt() to input.readNBytes(size.toInt()).also {
        if (it.size.toLong() != size) throw EOFException("truncated UMP part")
    }
}

private fun readUmpVarint(input: InputStream, allowEnd: Boolean): Long? {
    val first = input.read()
    if (first < 0) {
        if (allowEnd) return null
        throw EOFException("truncated UMP header")
    }
    fun next(): Long = input.read().also { if (it < 0) throw EOFException("truncated UMP varint") }.toLong()
    return when {
        first < 0x80 -> first.toLong()
        first < 0xC0 -> (first and 0x3F).toLong() or (next() shl 6)
        first < 0xE0 -> (first and 0x1F).toLong() or (next() shl 5) or (next() shl 13)
        first < 0xF0 -> (first and 0x0F).toLong() or (next() shl 4) or (next() shl 12) or (next() shl 20)
        else -> next() or (next() shl 8) or (next() shl 16) or (next() shl 24)
    }
}
