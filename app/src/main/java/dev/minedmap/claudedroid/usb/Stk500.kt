package dev.minedmap.claudedroid.usb

import java.io.ByteArrayOutputStream

/*
 * Kotlin port/adaptation of the STK500v1 / STK500v2 upload logic from
 * PhysicaloidLibrary (Copyright (C) 2013 Keisuke SUZUKI, Apache License 2.0),
 * itself derived from avrdude. Constants and command layouts follow
 * Stk500.java / Stk500V2.java / AvrConf.java; the I/O layer is rewritten
 * against [SerialLink]. See NOTICE.md.
 */

/** Blocking serial access used by the uploaders (backed by a UsbSerialPort or a fake in tests). */
interface SerialLink {
  fun write(data: ByteArray)
  /** Returns whatever arrived within [timeoutMs] (possibly empty). */
  fun read(max: Int, timeoutMs: Int): ByteArray
  fun setDtrRts(dtr: Boolean, rts: Boolean)
}

class FlashException(message: String) : Exception(message)

data class AvrBoard(val name: String, val baud: Int, val protocol: Int, val pageSize: Int, val flashSize: Int, val devCode: Int) {
  companion object {
    private val all = listOf(
      AvrBoard("uno", 115200, 1, 128, 32768, 0x86),
      AvrBoard("nano", 115200, 1, 128, 32768, 0x86),
      AvrBoard("nano_old", 57600, 1, 128, 30720, 0x86),
      AvrBoard("mega2560", 115200, 2, 256, 262144, 0),
    )
    fun byName(n: String): AvrBoard? = all.firstOrNull { it.name == n }
    val names get() = all.map { it.name }
  }
}

internal fun SerialLink.readExact(n: Int, timeoutMs: Int): ByteArray? {
  val out = ByteArrayOutputStream(n)
  val end = System.currentTimeMillis() + timeoutMs
  while (out.size() < n) {
    val left = end - System.currentTimeMillis()
    if (left <= 0) return null
    val b = read(n - out.size(), minOf(left.toInt(), 100).coerceAtLeast(1))
    out.write(b, 0, b.size)
  }
  return out.toByteArray()
}

internal fun SerialLink.drain(ms: Int) {
  val end = System.currentTimeMillis() + ms
  while (System.currentTimeMillis() < end) read(256, 20)
}

/** Arduino-style auto reset: DTR/RTS low->high edge, then give the bootloader time to start. */
internal fun SerialLink.resetPulse() {
  setDtrRts(false, false)
  Thread.sleep(250)
  setDtrRts(true, true)
  Thread.sleep(50)
}

/** STK500v1 (optiboot on Uno/Nano). */
class Stk500v1(private val link: SerialLink, private val board: AvrBoard, private val progress: (Int) -> Unit = {}) {
  private companion object {
    const val INSYNC = 0x14; const val OK = 0x10; const val EOP = 0x20
    const val GET_SYNC = 0x30; const val SET_DEVICE = 0x42; const val ENTER_PROG = 0x50; const val LEAVE_PROG = 0x51
    const val LOAD_ADDR = 0x55; const val PROG_PAGE = 0x64; const val READ_PAGE = 0x74
  }

  private fun cmd(bytes: IntArray, expectData: Int = 0, timeoutMs: Int = 1000): ByteArray {
    link.write(ByteArray(bytes.size) { bytes[it].toByte() })
    val r = link.readExact(2 + expectData, timeoutMs) ?: throw FlashException("STK500: no response to 0x${Integer.toHexString(bytes[0])}")
    if ((r[0].toInt() and 0xFF) != INSYNC) throw FlashException("STK500: not in sync (got 0x${Integer.toHexString(r[0].toInt() and 0xFF)})")
    if ((r[1 + expectData].toInt() and 0xFF) != OK) throw FlashException("STK500: command 0x${Integer.toHexString(bytes[0])} failed")
    return r.copyOfRange(1, 1 + expectData)
  }

  private fun sync(): Boolean {
    repeat(10) {
      link.drain(30)
      link.write(byteArrayOf(GET_SYNC.toByte(), EOP.toByte()))
      val r = link.readExact(2, 250)
      if (r != null && (r[0].toInt() and 0xFF) == INSYNC && (r[1].toInt() and 0xFF) == OK) return true
    }
    return false
  }

  fun upload(image: ByteArray) {
    if (image.size > board.flashSize) throw FlashException("image ${image.size} B exceeds flash ${board.flashSize} B")
    link.resetPulse()
    link.drain(100)
    if (!sync()) throw FlashException("STK500: no sync (wrong board/baud, or bootloader not running?)")
    // SET_DEVICE: layout from avrdude stk500.c, values for ATmega328P (optiboot ignores them).
    val d = IntArray(22)
    d[0] = SET_DEVICE; d[1] = board.devCode; d[2] = 0; d[3] = 0; d[4] = 1; d[5] = 1; d[6] = 1
    d[7] = 1; d[8] = 3; d[9] = 0xFF; d[10] = 0xFF; d[11] = 0xFF; d[12] = 0xFF
    d[13] = board.pageSize shr 8; d[14] = board.pageSize and 0xFF
    d[15] = 1024 shr 8; d[16] = 1024 and 0xFF
    d[17] = (board.flashSize ushr 24) and 0xFF; d[18] = (board.flashSize ushr 16) and 0xFF
    d[19] = (board.flashSize ushr 8) and 0xFF; d[20] = board.flashSize and 0xFF; d[21] = EOP
    cmd(d)
    cmd(intArrayOf(ENTER_PROG, EOP))
    val ps = board.pageSize
    val padded = ByteArray(((image.size + ps - 1) / ps) * ps) { 0xFF.toByte() }
    System.arraycopy(image, 0, padded, 0, image.size)
    var addr = 0
    while (addr < padded.size) {
      progress(addr * 100 / padded.size)
      val w = addr / 2
      cmd(intArrayOf(LOAD_ADDR, w and 0xFF, (w shr 8) and 0xFF, EOP))
      val pk = ByteArray(5 + ps + 1)
      pk[0] = PROG_PAGE.toByte(); pk[1] = (ps shr 8).toByte(); pk[2] = (ps and 0xFF).toByte(); pk[3] = 'F'.code.toByte()
      System.arraycopy(padded, addr, pk, 4, ps)
      pk[4 + ps] = EOP.toByte()
      link.write(pk.copyOf(5 + ps))
      val r = link.readExact(2, 2000) ?: throw FlashException("STK500: no ack for page at 0x${Integer.toHexString(addr)}")
      if ((r[0].toInt() and 0xFF) != INSYNC || (r[1].toInt() and 0xFF) != OK) throw FlashException("STK500: page write failed at 0x${Integer.toHexString(addr)}")
      addr += ps
    }
    // verify
    addr = 0
    while (addr < padded.size) {
      val w = addr / 2
      cmd(intArrayOf(LOAD_ADDR, w and 0xFF, (w shr 8) and 0xFF, EOP))
      val back = cmd(intArrayOf(READ_PAGE, ps shr 8, ps and 0xFF, 'F'.code, EOP), expectData = ps, timeoutMs = 2000)
      for (i in 0 until ps) if (back[i] != padded[addr + i]) throw FlashException("verify mismatch at 0x${Integer.toHexString(addr + i)}")
      addr += ps
    }
    progress(100)
    cmd(intArrayOf(LEAVE_PROG, EOP))
  }
}

/** STK500v2 framing (Wiring bootloader on Mega2560). */
object Stk500v2Frame {
  const val START = 0x1B; const val TOKEN = 0x0E

  fun build(seq: Int, body: ByteArray): ByteArray {
    val f = ByteArray(body.size + 6)
    f[0] = START.toByte(); f[1] = seq.toByte(); f[2] = (body.size shr 8).toByte(); f[3] = (body.size and 0xFF).toByte(); f[4] = TOKEN.toByte()
    System.arraycopy(body, 0, f, 5, body.size)
    var x = 0
    for (i in 0 until 5 + body.size) x = x xor (f[i].toInt() and 0xFF)
    f[5 + body.size] = x.toByte()
    return f
  }
}

class Stk500v2(private val link: SerialLink, private val board: AvrBoard, private val progress: (Int) -> Unit = {}) {
  private companion object {
    const val SIGN_ON = 0x01; const val LOAD_ADDRESS = 0x06; const val ENTER_PROGMODE_ISP = 0x10; const val LEAVE_PROGMODE_ISP = 0x11
    const val PROGRAM_FLASH_ISP = 0x13; const val READ_FLASH_ISP = 0x14
    const val STATUS_OK = 0x00
  }
  private var seq = 0

  /** Reads one framed answer body; null on timeout/bad frame. */
  private fun recv(timeoutMs: Int): ByteArray? {
    val end = System.currentTimeMillis() + timeoutMs
    fun left() = (end - System.currentTimeMillis()).toInt()
    while (left() > 0) {
      val s = link.readExact(1, left().coerceAtLeast(1)) ?: return null
      if ((s[0].toInt() and 0xFF) != Stk500v2Frame.START) continue
      val h = link.readExact(4, maxOf(left(), 50)) ?: return null
      if ((h[0].toInt() and 0xFF) != (seq and 0xFF)) continue
      if ((h[3].toInt() and 0xFF) != Stk500v2Frame.TOKEN) continue
      val n = ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
      if (n > 1024) continue
      val rest = link.readExact(n + 1, maxOf(left(), 100)) ?: return null
      var x = Stk500v2Frame.START
      for (b in h) x = x xor (b.toInt() and 0xFF)
      for (i in 0 until n) x = x xor (rest[i].toInt() and 0xFF)
      if (x != (rest[n].toInt() and 0xFF)) continue
      return rest.copyOf(n)
    }
    return null
  }

  private fun command(body: ByteArray, timeoutMs: Int = 2000): ByteArray {
    seq = (seq + 1) and 0xFF
    link.write(Stk500v2Frame.build(seq, body))
    val r = recv(timeoutMs) ?: throw FlashException("STK500v2: no response to cmd 0x${Integer.toHexString(body[0].toInt() and 0xFF)}")
    if (r.size < 2 || r[0] != body[0]) throw FlashException("STK500v2: unexpected answer")
    if ((r[1].toInt() and 0xFF) != STATUS_OK) throw FlashException("STK500v2: cmd 0x${Integer.toHexString(body[0].toInt() and 0xFF)} status 0x${Integer.toHexString(r[1].toInt() and 0xFF)}")
    return r
  }

  private fun signOn(): Boolean {
    repeat(10) {
      link.drain(30)
      try {
        val r = command(byteArrayOf(SIGN_ON.toByte()), 400)
        if (r.size >= 3) return true
      } catch (_: FlashException) {}
    }
    return false
  }

  private fun loadAddr(a: Int) = command(byteArrayOf(LOAD_ADDRESS.toByte(), (a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte()))

  fun upload(image: ByteArray) {
    if (image.size > board.flashSize) throw FlashException("image ${image.size} B exceeds flash ${board.flashSize} B")
    link.resetPulse()
    Thread.sleep(400) // wiring bootloader needs a moment after reset
    link.drain(100)
    if (!signOn()) throw FlashException("STK500v2: sign-on failed (wrong board/baud, or bootloader not running?)")
    // ATmega2560 from avrdude.conf / AvrConf.java: timeout 200, stabdelay 100, cmdexedelay 25, synchloops 32, bytedelay 0, pollvalue 0x53, pollindex 3
    command(byteArrayOf(ENTER_PROGMODE_ISP.toByte(), 200.toByte(), 100, 25, 32, 0, 0x53, 3, 0xAC.toByte(), 0x53, 0, 0))
    val ps = board.pageSize
    val extAddr = 1 shl 31 // bit 31: load extended address (>64 KiB flash)
    var addr = 0
    while (addr < image.size) {
      progress(addr * 100 / image.size)
      val n = minOf(ps, image.size - addr)
      var empty = true
      for (i in 0 until n) if (image[addr + i] != 0xFF.toByte()) { empty = false; break }
      if (!empty) {
        loadAddr(extAddr or (addr shr 1))
        val b = ByteArray(10 + n)
        b[0] = PROGRAM_FLASH_ISP.toByte(); b[1] = (n shr 8).toByte(); b[2] = (n and 0xFF).toByte()
        b[3] = (0x41 or 0x80).toByte(); b[4] = 10; b[5] = 0x40; b[6] = 0x4C; b[7] = 0x20; b[8] = 0; b[9] = 0
        System.arraycopy(image, addr, b, 10, n)
        command(b, 5000)
      }
      addr += ps
    }
    // verify (only non-empty pages; the bootloader erased pages per write)
    addr = 0
    while (addr < image.size) {
      val n = minOf(ps, image.size - addr)
      var empty = true
      for (i in 0 until n) if (image[addr + i] != 0xFF.toByte()) { empty = false; break }
      if (!empty) {
        loadAddr(extAddr or (addr shr 1))
        val r = command(byteArrayOf(READ_FLASH_ISP.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte(), 0x20), 3000)
        if (r.size < 2 + n) throw FlashException("STK500v2: short read-back")
        for (i in 0 until n) if (r[2 + i] != image[addr + i]) throw FlashException("verify mismatch at 0x${Integer.toHexString(addr + i)}")
      }
      addr += ps
    }
    progress(100)
    command(byteArrayOf(LEAVE_PROGMODE_ISP.toByte(), 1, 1))
  }
}
