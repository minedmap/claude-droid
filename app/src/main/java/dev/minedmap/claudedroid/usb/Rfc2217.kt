package dev.minedmap.claudedroid.usb

import java.io.ByteArrayOutputStream

/** Actions the TCP bridge must apply to the serial port. */
sealed interface SerialAction {
  data class Baud(val baud: Int) : SerialAction
  data class DataSize(val bits: Int) : SerialAction
  data class Parity(val parity: Int) : SerialAction   // UsbSerialPort.PARITY_* values
  data class StopSize(val stop: Int) : SerialAction   // UsbSerialPort.STOPBITS_* values
  data class Dtr(val on: Boolean) : SerialAction
  data class Rts(val on: Boolean) : SerialAction
  object Purge : SerialAction
}

/**
 * RFC 2217 (Telnet COM-PORT-OPTION) server-side codec, subset used by pyserial/esptool:
 * SET_BAUDRATE, SET_DATASIZE, SET_PARITY, SET_STOPSIZE, SET_CONTROL (DTR/RTS), PURGE_DATA.
 * NOTIFY_* and flow control subnegotiations are accepted and ignored.
 * Protocol constants follow RFC 2217 / RFC 854.
 */
class Rfc2217Codec {
  companion object {
    const val IAC = 255; const val DONT = 254; const val DO = 253; const val WONT = 252; const val WILL = 251
    const val SB = 250; const val SE = 240
    const val OPT_BINARY = 0; const val OPT_ECHO = 1; const val OPT_SGA = 3; const val OPT_COM_PORT = 44
    const val SET_BAUDRATE = 1; const val SET_DATASIZE = 2; const val SET_PARITY = 3; const val SET_STOPSIZE = 4
    const val SET_CONTROL = 5; const val PURGE_DATA = 12
    private val supported = setOf(OPT_BINARY, OPT_ECHO, OPT_SGA, OPT_COM_PORT)

    /** Escape 0xFF for the telnet stream. */
    fun escape(data: ByteArray, len: Int = data.size): ByteArray {
      val o = ByteArrayOutputStream(len + 8)
      for (i in 0 until len) { o.write(data[i].toInt() and 0xFF); if (data[i] == 0xFF.toByte()) o.write(IAC) }
      return o.toByteArray()
    }
  }

  class Result(val data: ByteArray, val reply: ByteArray, val actions: List<SerialAction>)

  private enum class St { DATA, IAC, OPT, SB, SB_IAC }
  private var st = St.DATA
  private var optCmd = 0
  private val sb = ByteArrayOutputStream()
  private val weAre = HashSet<Int>()   // options we announced WILL
  private val theyAre = HashSet<Int>() // options peer announced WILL

  /** Bytes to send on connect: offer the options pyserial's client expects. */
  fun greeting(): ByteArray {
    val o = ByteArrayOutputStream()
    for (opt in listOf(OPT_ECHO, OPT_SGA, OPT_BINARY, OPT_COM_PORT)) { o.write(IAC); o.write(WILL); o.write(opt); weAre.add(opt) }
    o.write(IAC); o.write(DO); o.write(OPT_BINARY); theyAre.add(OPT_BINARY)
    return o.toByteArray()
  }

  fun feed(inp: ByteArray, len: Int = inp.size): Result {
    val data = ByteArrayOutputStream()
    val reply = ByteArrayOutputStream()
    val actions = ArrayList<SerialAction>()
    for (i in 0 until len) {
      val c = inp[i].toInt() and 0xFF
      when (st) {
        St.DATA -> if (c == IAC) st = St.IAC else data.write(c)
        St.IAC -> when (c) {
          IAC -> { data.write(IAC); st = St.DATA }
          DO, DONT, WILL, WONT -> { optCmd = c; st = St.OPT }
          SB -> { sb.reset(); st = St.SB }
          else -> st = St.DATA
        }
        St.OPT -> { negotiate(optCmd, c, reply); st = St.DATA }
        St.SB -> if (c == IAC) st = St.SB_IAC else sb.write(c)
        St.SB_IAC -> when (c) {
          IAC -> { sb.write(IAC); st = St.SB }
          SE -> { handleSub(sb.toByteArray(), reply, actions); st = St.DATA }
          else -> st = St.DATA
        }
      }
    }
    return Result(data.toByteArray(), reply.toByteArray(), actions)
  }

  private fun negotiate(cmd: Int, opt: Int, out: ByteArrayOutputStream) {
    when (cmd) {
      DO -> if (opt in supported) {
        if (weAre.add(opt)) { out.write(IAC); out.write(WILL); out.write(opt) }
      } else { out.write(IAC); out.write(WONT); out.write(opt) }
      WILL -> if (opt in supported) {
        if (theyAre.add(opt)) { out.write(IAC); out.write(DO); out.write(opt) }
      } else { out.write(IAC); out.write(DONT); out.write(opt) }
      DONT -> if (weAre.remove(opt)) { out.write(IAC); out.write(WONT); out.write(opt) }
      WONT -> if (theyAre.remove(opt)) { out.write(IAC); out.write(DONT); out.write(opt) }
    }
  }

  private fun sub(cmd: Int, value: ByteArray): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(IAC); o.write(SB); o.write(OPT_COM_PORT); o.write(cmd + 100)
    for (b in value) { o.write(b.toInt() and 0xFF); if (b == 0xFF.toByte()) o.write(IAC) }
    o.write(IAC); o.write(SE)
    return o.toByteArray()
  }

  private fun handleSub(p: ByteArray, reply: ByteArrayOutputStream, actions: MutableList<SerialAction>) {
    if (p.size < 2 || (p[0].toInt() and 0xFF) != OPT_COM_PORT) return
    val cmd = p[1].toInt() and 0xFF
    val v = p.copyOfRange(2, p.size)
    fun u8() = if (v.isNotEmpty()) v[0].toInt() and 0xFF else 0
    when (cmd) {
      SET_BAUDRATE -> if (v.size >= 4) {
        val baud = ((v[0].toInt() and 0xFF) shl 24) or ((v[1].toInt() and 0xFF) shl 16) or ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
        if (baud > 0) actions.add(SerialAction.Baud(baud))
        reply.write(sub(cmd, v.copyOf(4)))
      }
      SET_DATASIZE -> { if (u8() in 5..8) actions.add(SerialAction.DataSize(u8())); reply.write(sub(cmd, v.copyOf(1))) }
      SET_PARITY -> {
        // RFC: 1 none, 2 odd, 3 even, 4 mark, 5 space -> usb-serial 0..4
        if (u8() in 1..5) actions.add(SerialAction.Parity(u8() - 1))
        reply.write(sub(cmd, v.copyOf(1)))
      }
      SET_STOPSIZE -> {
        // RFC: 1 = 1 bit, 2 = 2 bits, 3 = 1.5 bits; usb-serial: 1, 2, 3
        if (u8() in 1..3) actions.add(SerialAction.StopSize(u8()))
        reply.write(sub(cmd, v.copyOf(1)))
      }
      SET_CONTROL -> {
        when (u8()) {
          8 -> actions.add(SerialAction.Dtr(true))
          9 -> actions.add(SerialAction.Dtr(false))
          11 -> actions.add(SerialAction.Rts(true))
          12 -> actions.add(SerialAction.Rts(false))
        }
        reply.write(sub(cmd, v.copyOf(1)))
      }
      PURGE_DATA -> { actions.add(SerialAction.Purge); reply.write(sub(cmd, v.copyOf(1))) }
      else -> {} // NOTIFY_*, flow control, masks: ignored
    }
  }
}
