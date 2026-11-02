package dev.minedmap.claudedroid.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class HexUtilTest {
  @Test fun roundTrip() {
    val b = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())
    assertEquals("00017f80ff", HexUtil.encode(b))
    assertArrayEquals(b, HexUtil.decode("00 01:7F 0x80 FF"))
  }
  @Test fun rejectsOdd() { try { HexUtil.decode("abc"); fail() } catch (_: IllegalArgumentException) {} }
  @Test fun rejectsBadChar() { try { HexUtil.decode("zz"); fail() } catch (_: IllegalArgumentException) {} }
  @Test fun partialLength() { assertEquals("0102", HexUtil.encode(byteArrayOf(1, 2, 3), 2)) }
}

class IntelHexTest {
  @Test fun parsesDataAndFillsGaps() {
    val img = IntelHex.parse(":04000200AABBCCDDEC\n:00000001FF").data
    assertEquals(6, img.size)
    assertEquals(0xFF.toByte(), img[0]); assertEquals(0xAA.toByte(), img[2]); assertEquals(0xDD.toByte(), img[5])
  }
  @Test fun checksumEnforced() {
    try { IntelHex.parse(":04000200AABBCCDD75\n:00000001FF"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("checksum")) }
  }
  @Test fun needsEof() { try { IntelHex.parse(":04000200AABBCCDDEC"); fail() } catch (_: IllegalArgumentException) {} }
  @Test fun extLinear() {
    // 0x0001_0000 base, 1 byte at offset 0 -> image length 0x10001
    val img = IntelHex.parse(":020000040001F9\n:0100000055AA\n:00000001FF").data
    assertEquals(0x10001, img.size)
    assertEquals(0x55.toByte(), img[0x10000])
  }
  @Test fun realRecords() {
    val img = IntelHex.parse(":100000000C9434000C943E000C943E000C943E0082\n:00000001FF").data
    assertEquals(16, img.size); assertEquals(0x0C.toByte(), img[0]); assertEquals(0x34.toByte(), img[2])
  }
}

class RingBufferTest {
  @Test fun overwritesOldest() {
    val r = RingBuffer(4)
    r.write(byteArrayOf(1, 2, 3)); r.write(byteArrayOf(4, 5, 6))
    assertEquals(4, r.available())
    assertArrayEquals(byteArrayOf(3, 4, 5, 6), r.read(10))
    assertEquals(2L, r.dropped)
    assertEquals(0, r.available())
  }
  @Test fun partialReadsKeepOrder() {
    val r = RingBuffer(8)
    r.write(byteArrayOf(1, 2, 3, 4)); assertArrayEquals(byteArrayOf(1, 2), r.read(2))
    r.write(byteArrayOf(5, 6, 7, 8, 9, 10)); assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10), r.read(100))
  }
  @Test fun hugeWriteKeepsTail() {
    val r = RingBuffer(3); r.write(byteArrayOf(1, 2, 3, 4, 5)); assertArrayEquals(byteArrayOf(3, 4, 5), r.read(9))
  }
}

class HttpParseTest {
  private fun req(s: String, max: Int = 1024) = HttpParse.readRequest(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)), max)
  @Test fun getWithQuery() {
    val r = req("GET /serial/read?device=%2Fdev%2Fbus%2Fusb%2F001%2F002&max=10 HTTP/1.1\r\nX-Token: abc\r\n\r\n")
    assertEquals("GET", r.method); assertEquals("/serial/read", r.path)
    assertEquals("/dev/bus/usb/001/002", r.query["device"]); assertEquals("10", r.query["max"])
    assertEquals("abc", r.headers["x-token"])
  }
  @Test fun postBody() {
    val r = req("POST /usb/control HTTP/1.1\r\nContent-Length: 7\r\n\r\n{\"a\":1}")
    assertEquals("{\"a\":1}", String(r.body))
  }
  @Test fun bodyTooLarge() {
    try { req("POST /x HTTP/1.1\r\nContent-Length: 2000\r\n\r\n", 1024); fail() } catch (e: HttpError) { assertEquals(413, e.status) }
  }
  @Test fun truncatedBody() {
    try { req("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"); fail() } catch (e: HttpError) { assertEquals(400, e.status) }
  }
  @Test fun chunkedRejected() {
    try { req("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"); fail() } catch (e: HttpError) { assertEquals(411, e.status) }
  }
  @Test fun token() {
    assertTrue(HttpParse.tokenOk("abc", "abc"))
    assertFalse(HttpParse.tokenOk("abc", "abd")); assertFalse(HttpParse.tokenOk("abc", null)); assertFalse(HttpParse.tokenOk("", ""))
  }
  @Test fun responseFormat() {
    val s = String(HttpParse.response(401, "{\"error\":\"x\"}"), Charsets.UTF_8)
    assertTrue(s.startsWith("HTTP/1.1 401 Unauthorized\r\n")); assertTrue(s.contains("Content-Length: 13")); assertTrue(s.endsWith("{\"error\":\"x\"}"))
  }
}

class Rfc2217Test {
  private fun iac(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
  @Test fun plainDataAndEscapedIac() {
    val c = Rfc2217Codec()
    val r = c.feed(iac(0x41, 255, 255, 0x42))
    assertArrayEquals(iac(0x41, 255, 0x42), r.data)
    assertArrayEquals(iac(1, 255, 255), Rfc2217Codec.escape(iac(1, 255)))
  }
  @Test fun setBaudrate() {
    val c = Rfc2217Codec()
    val r = c.feed(iac(255, 250, 44, 1, 0, 1, 0xC2, 0, 255, 240)) // 115200 = 0x0001C200
    assertEquals(listOf<SerialAction>(SerialAction.Baud(115200)), r.actions)
    assertArrayEquals(iac(255, 250, 44, 101, 0, 1, 0xC2, 0, 255, 240), r.reply)
  }
  @Test fun controlLinesAndPurge() {
    val c = Rfc2217Codec()
    val r = c.feed(iac(255, 250, 44, 5, 8, 255, 240, 255, 250, 44, 5, 12, 255, 240, 255, 250, 44, 12, 3, 255, 240))
    assertEquals(listOf(SerialAction.Dtr(true), SerialAction.Rts(false), SerialAction.Purge), r.actions)
  }
  @Test fun parityStopSize() {
    val c = Rfc2217Codec()
    val r = c.feed(iac(255, 250, 44, 3, 3, 255, 240, 255, 250, 44, 4, 2, 255, 240, 255, 250, 44, 2, 7, 255, 240))
    assertEquals(listOf(SerialAction.Parity(2), SerialAction.StopSize(2), SerialAction.DataSize(7)), r.actions)
  }
  @Test fun splitAcrossFeeds() {
    val c = Rfc2217Codec()
    val a = c.feed(iac(255, 250, 44, 1, 0, 0))
    assertTrue(a.actions.isEmpty())
    val b = c.feed(iac(0x25, 0x80, 255, 240, 0x5A))
    assertEquals(listOf<SerialAction>(SerialAction.Baud(9600)), b.actions)
    assertArrayEquals(iac(0x5A), b.data)
  }
  @Test fun negotiationNoLoop() {
    val c = Rfc2217Codec()
    c.greeting()
    // peer says DO COM-PORT: we already announced WILL, so no reply (avoids loops)
    assertEquals(0, c.feed(iac(255, 253, 44)).reply.size)
    // unknown option refused
    assertArrayEquals(iac(255, 252, 99), c.feed(iac(255, 253, 99)).reply)
  }
  @Test fun escapedFfInsideSubneg() {
    val c = Rfc2217Codec()
    val r = c.feed(iac(255, 250, 44, 1, 0, 0, 0xFF, 0xFF, 255, 240))
    // unescaped payload is 00 00 FF = only 3 baud bytes -> ignored
    assertTrue(r.actions.isEmpty())
  }
}

class Stk500Test {
  @Test fun v2FrameChecksum() {
    val f = Stk500v2Frame.build(1, byteArrayOf(0x01))
    assertArrayEquals(byteArrayOf(0x1B, 1, 0, 1, 0x0E, 0x01, (0x1B xor 1 xor 0 xor 1 xor 0x0E xor 1).toByte()), f)
  }

  /** Minimal optiboot-like STK500v1 target. */
  private class FakeUno(val flashSize: Int, val page: Int) : SerialLink {
    val mem = ByteArray(flashSize) { 0xFF.toByte() }
    private val out = java.util.ArrayDeque<Byte>()
    private var buf = ArrayList<Int>()
    private var addr = 0
    var left = false
    override fun setDtrRts(dtr: Boolean, rts: Boolean) {}
    override fun read(max: Int, timeoutMs: Int): ByteArray {
      val n = minOf(max, out.size)
      if (n == 0) { Thread.sleep(1); return ByteArray(0) }
      return ByteArray(n) { out.poll() }
    }
    private fun ok() { out.add(0x14); out.add(0x10) }
    override fun write(data: ByteArray) {
      for (b in data) buf.add(b.toInt() and 0xFF)
      while (buf.isNotEmpty()) {
        val need = when (buf[0]) {
          0x30, 0x50, 0x51 -> 2
          0x42 -> 22
          0x55 -> 4
          0x64 -> if (buf.size >= 3) 3 + ((buf[1] shl 8) or buf[2]) + 1 + 1 else Int.MAX_VALUE
          0x74 -> 5
          else -> throw IllegalStateException("bad cmd ${buf[0]}")
        }
        if (buf.size < need) return
        val c = ArrayList(buf.subList(0, need)); buf = ArrayList(buf.subList(need, buf.size))
        when (c[0]) {
          0x55 -> { addr = (c[1] or (c[2] shl 8)) * 2; ok() }
          0x64 -> { val n = (c[1] shl 8) or c[2]; for (i in 0 until n) mem[addr + i] = c[4 + i].toByte(); ok() }
          0x74 -> { val n = (c[1] shl 8) or c[2]; out.add(0x14); for (i in 0 until n) out.add(mem[addr + i]); out.add(0x10) }
          0x51 -> { left = true; ok() }
          else -> ok()
        }
      }
    }
  }

  @Test fun v1UploadWritesAndVerifies() {
    val uno = AvrBoard.byName("uno")!!
    val fake = FakeUno(uno.flashSize, uno.pageSize)
    val img = ByteArray(300) { (it * 7).toByte() }
    Stk500v1(fake, uno).upload(img)
    for (i in img.indices) assertEquals("byte $i", img[i], fake.mem[i])
    assertEquals(0xFF.toByte(), fake.mem[384]) // padding page written as FF, beyond that untouched
    assertTrue(fake.left)
  }

  @Test fun v1RejectsOversize() {
    val uno = AvrBoard.byName("uno")!!
    try { Stk500v1(FakeUno(32768, 128), uno).upload(ByteArray(40000)); fail() } catch (e: FlashException) { assertTrue(e.message!!.contains("exceeds")) }
  }

  @Test fun boardTable() {
    assertEquals(57600, AvrBoard.byName("nano_old")!!.baud)
    assertEquals(2, AvrBoard.byName("mega2560")!!.protocol)
    assertEquals(null, AvrBoard.byName("leonardo"))
  }
}
