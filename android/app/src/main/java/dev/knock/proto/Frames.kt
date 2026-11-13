package dev.knock.proto

import java.io.DataInputStream
import java.io.IOException

/** Frame layout and type codes (protocol/PROTOCOL.md §2). */
object Frames {
    const val MAX = 1 shl 20

    const val PAIR_COMMIT = 0x01
    const val PAIR_MAC = 0x02
    const val PAIR_PHONE = 0x03
    const val PAIR_OK = 0x04
    const val HELLO = 0x10
    const val HELLO_MAC = 0x11
    const val HELLO_FIN = 0x12
    const val SEALED = 0x20

    const val ROUND_START = 0x30
    const val SHOW = 0x31
    const val SHOWN = 0x32
    const val ROUND_RESULT = 0x33
    const val FILE_HEADER = 0x40
    const val FILE_CHUNK = 0x41
    const val FILE_END = 0x42
    const val FILE_SAVED = 0x43

    fun encode(type: Int, body: ByteArray): ByteArray = u32(1 + body.size) + byteArrayOf(type.toByte()) + body

    /** Reads one frame: (type, body). */
    fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val n = input.readInt()
        if (n < 1 || n > MAX) throw IOException("bad frame length $n")
        val type = input.readUnsignedByte()
        val body = ByteArray(n - 1)
        input.readFully(body)
        return type to body
    }
}
