package dev.minedmap.claudedroid.usb

/** Hex string helpers shared by the HTTP API. */
object HexUtil {
  fun encode(b: ByteArray, len: Int = b.size): String {
    val sb = StringBuilder(len * 2)
    for (i in 0 until len) {
      val v = b[i].toInt() and 0xFF
      sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
  }

  /** Accepts upper/lower case; spaces, ':' and '0x' prefixes are ignored. Throws IllegalArgumentException on bad input. */
  fun decode(s: String): ByteArray {
    val c = s.replace(Regex("0x|[\\s:]", RegexOption.IGNORE_CASE), "")
    require(c.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(c.length / 2)
    for (i in out.indices) {
      val hi = Character.digit(c[i * 2], 16)
      val lo = Character.digit(c[i * 2 + 1], 16)
      require(hi >= 0 && lo >= 0) { "bad hex char" }
      out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
  }
}

/** Parsed Intel HEX image: contiguous bytes from address 0 to the highest data address; gaps are 0xFF. */
class HexImage(val data: ByteArray)

/** Minimal Intel HEX parser (record types 00 data, 01 EOF, 02 ext segment, 04 ext linear). */
object IntelHex {
  const val MAX_SIZE = 4 * 1024 * 1024

  fun parse(text: String): HexImage {
    var base = 0L
    var max = 0
    val chunks = ArrayList<Pair<Int, ByteArray>>()
    var eof = false
    for ((n, raw) in text.lineSequence().withIndex()) {
      val line = raw.trim()
      if (line.isEmpty()) continue
      require(line[0] == ':') { "line ${n + 1}: missing ':'" }
      val rec = try { HexUtil.decode(line.substring(1)) } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("line ${n + 1}: ${e.message}")
      }
      require(rec.size >= 5) { "line ${n + 1}: too short" }
      val len = rec[0].toInt() and 0xFF
      require(rec.size == len + 5) { "line ${n + 1}: length mismatch" }
      var sum = 0
      for (b in rec) sum += b.toInt() and 0xFF
      require(sum and 0xFF == 0) { "line ${n + 1}: bad checksum" }
      val addr = ((rec[1].toInt() and 0xFF) shl 8) or (rec[2].toInt() and 0xFF)
      when (rec[3].toInt() and 0xFF) {
        0 -> {
          val abs = base + addr
          require(abs + len <= MAX_SIZE) { "image too large" }
          chunks.add(abs.toInt() to rec.copyOfRange(4, 4 + len))
          if (abs + len > max) max = (abs + len).toInt()
        }
        1 -> { eof = true }
        2 -> { require(len == 2) { "line ${n + 1}: bad ext segment" }
          base = (((rec[4].toInt() and 0xFF) shl 8) or (rec[5].toInt() and 0xFF)).toLong() * 16 }
        4 -> { require(len == 2) { "line ${n + 1}: bad ext linear" }
          base = (((rec[4].toInt() and 0xFF) shl 8) or (rec[5].toInt() and 0xFF)).toLong() shl 16 }
        3, 5 -> {}
        else -> throw IllegalArgumentException("line ${n + 1}: unknown record type")
      }
      if (eof) break
    }
    require(eof) { "missing EOF record" }
    val out = ByteArray(max) { 0xFF.toByte() }
    for ((a, d) in chunks) System.arraycopy(d, 0, out, a, d.size)
    return HexImage(out)
  }
}

/** Fixed-size byte ring: keeps the newest [cap] bytes, reads consume from the oldest. Thread-safe. */
class RingBuffer(private val cap: Int) {
  private val buf = ByteArray(cap)
  private var start = 0
  private var size = 0
  var dropped = 0L; private set

  @Synchronized fun write(src: ByteArray, len: Int = src.size) {
    var off = 0
    var n = len
    if (n > cap) { dropped += (n - cap); off = n - cap; n = cap }
    val over = size + n - cap
    if (over > 0) { start = (start + over) % cap; size -= over; dropped += over }
    var pos = (start + size) % cap
    for (i in 0 until n) { buf[pos] = src[off + i]; pos = (pos + 1) % cap }
    size += n
  }

  @Synchronized fun available(): Int = size

  @Synchronized fun read(max: Int): ByteArray {
    val n = minOf(max, size)
    val out = ByteArray(n)
    for (i in 0 until n) out[i] = buf[(start + i) % cap]
    start = (start + n) % cap
    size -= n
    return out
  }

  @Synchronized fun clear() { start = 0; size = 0 }
}
