package io.github.thinke.biwurth.sources

import io.github.thinke.biwurth.config.SourceConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Reads Modbus TCP registers every interval: holding and input registers as int16, uint16,
 * int32, uint32 or float32 (big or little word order), times a scale; coils and discrete inputs
 * as on/off. A coil field is also a button: a tap switches it.
 */
internal class ModbusSource(private val source: SourceConfig, private val sources: Sources) {
    private val lock = Mutex() // one request at a time on the connection: polls and button taps
    private var transaction = 0

    suspend fun run() {
        val host = source.settings.optString("host").trim().ifEmpty { error("No host") }
        val port = source.settings.optInt("port", 502)
        val unit = source.settings.optInt("unit", 1)
        Socket().use { socket ->
            runInterruptible {
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 5_000
            }
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            for (field in source.fields.filter { it.optString("kind") == "coil" }) {
                val id = sources.entityId(source, field)
                sources.setAction(id) {
                    val on = sources.entities.value[id]?.state == "on"
                    lock.withLock {
                        runInterruptible { request(input, output, unit, 5, field.optInt("address"), if (on) 0x0000 else 0xFF00) }
                    }
                }
            }
            while (true) {
                var count = 0
                for (field in source.fields) {
                    val state = lock.withLock { runInterruptible { read(input, output, unit, field) } }
                    sources.publish(source, field, state)
                    count++
                }
                sources.ok(source, count)
                delay(source.settings.optInt("interval", 10).coerceIn(1, 86_400) * 1000L)
            }
        }
    }

    private fun read(input: DataInputStream, output: DataOutputStream, unit: Int, field: org.json.JSONObject): String {
        val address = field.optInt("address")
        return when (field.optString("kind", "holding")) {
            "coil", "discrete" -> {
                val function = if (field.optString("kind") == "coil") 1 else 2
                val data = request(input, output, unit, function, address, 1)
                if (data[1].toInt() and 1 == 1) "on" else "off"
            }
            else -> {
                val type = field.optString("type", "int16")
                val words = if (type in setOf("int32", "uint32", "float32")) 2 else 1
                val function = if (field.optString("kind") == "input") 4 else 3
                val data = request(input, output, unit, function, address, words)
                // data[0] is the byte count; registers follow, each big-endian.
                val registers = ByteArray(words * 2).also { System.arraycopy(data, 1, it, 0, words * 2) }
                // "little" word order: the low register comes first, so swap the two.
                if (words == 2 && field.optString("order", "big") == "little") {
                    val first = registers.copyOfRange(0, 2)
                    registers.copyInto(registers, 0, 2, 4)
                    first.copyInto(registers, 2)
                }
                val buffer = ByteBuffer.wrap(registers)
                val raw: Double = when (type) {
                    "uint16" -> (buffer.short.toInt() and 0xFFFF).toDouble()
                    "int32" -> buffer.int.toDouble()
                    "uint32" -> (buffer.int.toLong() and 0xFFFFFFFFL).toDouble()
                    "float32" -> buffer.float.toDouble()
                    else -> buffer.short.toDouble()
                }
                val value = raw * field.optDouble("scale", 1.0).takeUnless { it.isNaN() }.let { it ?: 1.0 }
                JsonPath.stateOf(value) ?: "0"
            }
        }
    }

    /** One Modbus TCP request; returns the reply's data after the function code. */
    private fun request(input: DataInputStream, output: DataOutputStream, unit: Int, function: Int, address: Int, value: Int): ByteArray {
        val id = (++transaction) and 0xFFFF
        output.writeShort(id)
        output.writeShort(0) // protocol
        output.writeShort(6) // length: unit, function, address, value
        output.writeByte(unit)
        output.writeByte(function)
        output.writeShort(address)
        output.writeShort(value)
        output.flush()

        val replyId = input.readUnsignedShort()
        input.readUnsignedShort()
        val length = input.readUnsignedShort()
        input.readUnsignedByte() // unit
        val replyFunction = input.readUnsignedByte()
        val data = ByteArray(length - 2).also { input.readFully(it) }
        if (replyId != id) throw IOException("Modbus reply out of order")
        if (replyFunction and 0x80 != 0) throw IOException("Modbus exception ${data.firstOrNull()?.toInt() ?: 0} at address $address")
        return data
    }
}
