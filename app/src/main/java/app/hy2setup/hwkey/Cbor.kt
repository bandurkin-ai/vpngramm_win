package app.hy2setup.hwkey

import java.io.ByteArrayOutputStream

/**
 * Минимальный CBOR для CTAP2: целые, байтовые и текстовые строки, массивы, словари, true/false/null.
 * Словари кодируются канонически (ключи по длине кодировки, затем побайтно) — этого требует CTAP2.
 */
object Cbor {
    fun encode(v: Any?): ByteArray = ByteArrayOutputStream().also { write(it, v) }.toByteArray()

    private fun head(out: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        when {
            n < 24 -> out.write(m or n.toInt())
            n < 0x100 -> { out.write(m or 24); out.write(n.toInt()) }
            n < 0x10000 -> { out.write(m or 25); out.write((n shr 8).toInt()); out.write(n.toInt() and 0xff) }
            n < 0x100000000L -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write((n shr s).toInt() and 0xff) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write((n shr s).toInt() and 0xff) }
        }
    }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0xf6)
            true -> out.write(0xf5)
            false -> out.write(0xf4)
            is Int -> write(out, v.toLong())
            is Long -> if (v >= 0) head(out, 0, v) else head(out, 1, -1 - v)
            is ByteArray -> { head(out, 2, v.size.toLong()); out.write(v) }
            is String -> v.toByteArray(Charsets.UTF_8).let { head(out, 3, it.size.toLong()); out.write(it) }
            is List<*> -> { head(out, 4, v.size.toLong()); v.forEach { write(out, it) } }
            is Map<*, *> -> {
                head(out, 5, v.size.toLong())
                v.entries.map { encode(it.key) to encode(it.value) }
                    .sortedWith { a, b -> if (a.first.size != b.first.size) a.first.size - b.first.size else compareBytes(a.first, b.first) }
                    .forEach { (k, value) -> out.write(k); out.write(value) }
            }
            else -> throw IllegalArgumentException("CBOR: ${v.javaClass}")
        }
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return 0
    }

    /** Чтение с позиции: значение (Long, ByteArray, String, List, Map, Boolean, null) и где оно кончилось. */
    class Reader(private val b: ByteArray, var pos: Int = 0) {
        private fun u8() = b[pos++].toInt() and 0xff
        private fun arg(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> u8().toLong()
            info == 25 -> ((u8() shl 8) or u8()).toLong()
            info == 26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }
            info == 27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }
            else -> throw HwKeyException("Непонятный ответ токена (CBOR).")
        }

        fun read(): Any? {
            val ib = u8()
            val major = ib shr 5
            val info = ib and 0x1f
            return when (major) {
                0 -> arg(info)
                1 -> -1 - arg(info)
                2 -> { val n = arg(info).toInt(); b.copyOfRange(pos, pos + n).also { pos += n } }
                3 -> { val n = arg(info).toInt(); String(b, pos, n, Charsets.UTF_8).also { pos += n } }
                4 -> List(arg(info).toInt()) { read() }
                5 -> { val n = arg(info).toInt(); LinkedHashMap<Any?, Any?>().apply { repeat(n) { val k = read(); put(k, read()) } } }
                7 -> when (info) { 20 -> false; 21 -> true; 22, 23 -> null; else -> throw HwKeyException("Непонятный ответ токена (CBOR).") }
                else -> throw HwKeyException("Непонятный ответ токена (CBOR).")
            }
        }
    }

    fun decode(b: ByteArray): Any? = Reader(b).read()
}

/** Доступ к словарю CBOR по целому ключу. */
internal fun Map<*, *>.at(k: Int): Any? = this[k.toLong()]
