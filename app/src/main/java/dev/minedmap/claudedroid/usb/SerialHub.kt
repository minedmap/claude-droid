package dev.minedmap.claudedroid.usb

import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** One open serial port with a background reader and a 1 MiB ring buffer. */
class SerialSession(val device: UsbDevice, val portNo: Int, val port: UsbSerialPort) : SerialInputOutputManager.Listener {
  val key get() = keyOf(device.deviceName, portNo)
  val ring = RingBuffer(1 shl 20)
  @Volatile var sink: ((ByteArray) -> Unit)? = null
  @Volatile var error: String? = null
  @Volatile var baud = 115200
  @Volatile var dataBits = 8
  @Volatile var stopBits = 1
  @Volatile var parity = 0
  private var mgr: SerialInputOutputManager? = null

  companion object { fun keyOf(dev: String, port: Int) = "$dev#$port" }

  fun startIo() {
    val m = SerialInputOutputManager(port, this)
    mgr = m
    m.start()
  }

  override fun onNewData(data: ByteArray) {
    ring.write(data)
    try { sink?.invoke(data) } catch (_: Throwable) {}
  }

  override fun onRunError(e: Exception) { error = e.message ?: e.javaClass.simpleName }

  fun write(b: ByteArray) {
    val m = mgr ?: throw IOException("session not running")
    m.writeAsync(b)
  }

  fun applyParams() { port.setParameters(baud, dataBits, stopBits, parity) }

  fun close() {
    try { mgr?.stop() } catch (_: Throwable) {}
    try { port.close() } catch (_: Throwable) {}
  }
}

/** Link over a raw (not manager-backed) port for STK500 uploads. */
private class PortLink(val port: UsbSerialPort) : SerialLink {
  override fun write(data: ByteArray) { port.write(data, 3000) }
  override fun read(max: Int, timeoutMs: Int): ByteArray {
    val buf = ByteArray(max.coerceIn(1, 4096))
    val n = port.read(buf, timeoutMs)
    return if (n <= 0) ByteArray(0) else buf.copyOf(n)
  }
  override fun setDtrRts(dtr: Boolean, rts: Boolean) {
    try { port.setDTR(dtr) } catch (_: Exception) {}
    try { port.setRTS(rts) } catch (_: Exception) {}
  }
}

/** Loopback TCP forwarder for one serial session (raw or RFC 2217). */
class SerialTcp(private val s: SerialSession, val mode: String, port: Int) {
  private val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
  val tcpPort get() = server.localPort
  @Volatile private var client: Socket? = null
  @Volatile private var closed = false

  init {
    Thread({ acceptLoop() }, "usb-tcp-accept").apply { isDaemon = true; start() }
  }

  private fun acceptLoop() {
    while (!closed) {
      val c = try { server.accept() } catch (_: IOException) { return }
      // one client at a time: a second local connection must not hijack an active session
      if (client != null) { try { c.close() } catch (_: Throwable) {}; continue }
      client = c
      Thread({ serve(c) }, "usb-tcp-client").apply { isDaemon = true; start() }
    }
  }

  private fun serve(c: Socket) {
    val rfc = mode == "rfc2217"
    val codec = if (rfc) Rfc2217Codec() else null
    try {
      c.tcpNoDelay = true
      val out = c.getOutputStream()
      val lock = Any()
      fun send(b: ByteArray) { synchronized(lock) { out.write(b); out.flush() } }
      if (codec != null) send(codec.greeting())
      s.sink = { d -> try { send(if (rfc) Rfc2217Codec.escape(d) else d) } catch (_: IOException) {} }
      val inp = c.getInputStream()
      val buf = ByteArray(4096)
      while (true) {
        val n = inp.read(buf)
        if (n < 0) break
        if (codec == null) { s.write(buf.copyOf(n)); continue }
        val r = codec.feed(buf, n)
        if (r.reply.isNotEmpty()) send(r.reply)
        for (a in r.actions) apply(a)
        if (r.data.isNotEmpty()) s.write(r.data)
      }
    } catch (_: IOException) {
    } finally {
      if (client === c) { s.sink = null; client = null }
      try { c.close() } catch (_: Throwable) {}
    }
  }

  private fun apply(a: SerialAction) {
    try {
      when (a) {
        is SerialAction.Baud -> { s.baud = a.baud; s.applyParams() }
        is SerialAction.DataSize -> { s.dataBits = a.bits; s.applyParams() }
        is SerialAction.Parity -> { s.parity = a.parity; s.applyParams() }
        is SerialAction.StopSize -> { s.stopBits = a.stop; s.applyParams() }
        is SerialAction.Dtr -> s.port.setDTR(a.on)
        is SerialAction.Rts -> s.port.setRTS(a.on)
        is SerialAction.Purge -> s.ring.clear()
      }
    } catch (_: Throwable) {}
  }

  fun close() {
    closed = true
    try { server.close() } catch (_: Throwable) {}
    client?.let { try { it.close() } catch (_: Throwable) {} }
  }
}

class SerialHub(private val hub: UsbHub) {
  private val sessions = ConcurrentHashMap<String, SerialSession>()
  private val tcps = ConcurrentHashMap<String, SerialTcp>()

  private fun parity(s: String) = when (s.lowercase()) {
    "none" -> UsbSerialPort.PARITY_NONE; "odd" -> UsbSerialPort.PARITY_ODD; "even" -> UsbSerialPort.PARITY_EVEN
    "mark" -> UsbSerialPort.PARITY_MARK; "space" -> UsbSerialPort.PARITY_SPACE
    else -> throw UsbApiException(400, "bad parity: $s")
  }

  private fun stop(n: Double) = when (n) {
    1.0 -> UsbSerialPort.STOPBITS_1; 1.5 -> UsbSerialPort.STOPBITS_1_5; 2.0 -> UsbSerialPort.STOPBITS_2
    else -> throw UsbApiException(400, "bad stopBits: $n")
  }

  fun session(devId: String?, portNo: Int): SerialSession {
    val key = SerialSession.keyOf(devId ?: "", portNo)
    return sessions[key] ?: throw UsbApiException(404, "serial port not open: $key (call /serial/open)")
  }

  @Synchronized fun open(b: JSONObject): JSONObject {
    val d = hub.device(b.optString("device", ""))
    val portNo = b.optInt("port", 0)
    val key = SerialSession.keyOf(d.deviceName, portNo)
    val baud = b.optInt("baud", 115200)
    val bits = b.optInt("dataBits", 8)
    val sb = stop(b.optDouble("stopBits", 1.0))
    val par = parity(b.optString("parity", "none"))
    var s = sessions[key]
    if (s == null) {
      val port = hub.port(d, portNo)
      val conn = hub.requireOpen(d)
      try { port.open(conn) } catch (e: Exception) { conn.close(); throw UsbApiException(500, "port open failed: ${e.message}") }
      s = SerialSession(d, portNo, port)
      try {
        s.baud = baud; s.dataBits = bits; s.stopBits = sb; s.parity = par
        s.applyParams()
        if (b.has("dtr")) port.setDTR(b.getBoolean("dtr"))
        if (b.has("rts")) port.setRTS(b.getBoolean("rts"))
        s.startIo()
      } catch (e: Exception) { s.close(); throw UsbApiException(500, "port setup failed: ${e.message}") }
      sessions[key] = s
    } else {
      s.baud = baud; s.dataBits = bits; s.stopBits = sb; s.parity = par
      try {
        s.applyParams()
        if (b.has("dtr")) s.port.setDTR(b.getBoolean("dtr"))
        if (b.has("rts")) s.port.setRTS(b.getBoolean("rts"))
      } catch (e: Exception) { throw UsbApiException(500, "setParameters failed: ${e.message}") }
    }
    return JSONObject().put("open", true).put("key", key).put("baud", baud)
  }

  fun write(b: JSONObject): JSONObject {
    val s = session(b.optString("device", ""), b.optInt("port", 0))
    val data = when {
      b.has("hex") -> HexUtil.decode(b.getString("hex"))
      b.has("text") -> b.getString("text").toByteArray(Charsets.UTF_8)
      else -> throw UsbApiException(400, "text or hex required")
    }
    try { s.write(data) } catch (e: IOException) { throw UsbApiException(500, "write failed: ${e.message}") }
    return JSONObject().put("written", data.size)
  }

  fun read(q: Map<String, String>): JSONObject {
    val s = session(q["device"], q["port"]?.toIntOrNull() ?: 0)
    val max = (q["max"]?.toIntOrNull() ?: 4096).coerceIn(1, 1 shl 20)
    val wait = (q["waitMs"]?.toLongOrNull() ?: 0L).coerceIn(0L, 30_000L)
    val end = System.currentTimeMillis() + wait
    while (s.ring.available() == 0 && System.currentTimeMillis() < end && s.error == null) Thread.sleep(20)
    val data = s.ring.read(max)
    val o = JSONObject().put("text", String(data, Charsets.UTF_8)).put("hex", HexUtil.encode(data)).put("bytes", data.size)
    s.error?.let { o.put("error", it) }
    if (s.ring.dropped > 0) o.put("dropped", s.ring.dropped)
    return o
  }

  fun lines(b: JSONObject): JSONObject {
    val s = session(b.optString("device", ""), b.optInt("port", 0))
    try {
      if (b.has("dtr")) s.port.setDTR(b.getBoolean("dtr"))
      if (b.has("rts")) s.port.setRTS(b.getBoolean("rts"))
    } catch (e: Exception) { throw UsbApiException(500, "set lines failed: ${e.message}") }
    return JSONObject().put("ok", true)
  }

  @Synchronized fun close(b: JSONObject): JSONObject {
    val key = SerialSession.keyOf(b.optString("device", ""), b.optInt("port", 0))
    closeKey(key)
    return JSONObject().put("closed", key)
  }

  private fun closeKey(key: String) {
    tcps.remove(key)?.close()
    sessions.remove(key)?.close()
  }

  @Synchronized fun closeDevice(devId: String) {
    for (k in sessions.keys.filter { it.startsWith("$devId#") }) closeKey(k)
  }

  @Synchronized fun closeAll() { for (k in sessions.keys.toList()) closeKey(k) }

  @Synchronized fun tcp(b: JSONObject): JSONObject {
    val mode = b.optString("mode", "raw")
    if (mode != "raw" && mode != "rfc2217") throw UsbApiException(400, "mode must be raw or rfc2217")
    if (!b.has("baud")) b.put("baud", 115200)
    val o = open(b)
    val key = o.getString("key")
    val s = sessions[key]!!
    tcps.remove(key)?.close()
    val t = try { SerialTcp(s, mode, b.optInt("tcpPort", 0)) } catch (e: IOException) { throw UsbApiException(500, "tcp bind failed: ${e.message}") }
    tcps[key] = t
    val url = if (mode == "rfc2217") "rfc2217://127.0.0.1:${t.tcpPort}" else "socket://127.0.0.1:${t.tcpPort}"
    return JSONObject().put("tcpPort", t.tcpPort).put("mode", mode).put("url", url).put("key", key)
  }

  fun flashAvr(b: JSONObject): JSONObject {
    val d = hub.device(b.optString("device", ""))
    val boardName = b.optString("board", "uno")
    if (boardName == "leonardo") throw UsbApiException(400, "leonardo/micro (AVR109 bootloader) is not supported; use uno, nano, nano_old or mega2560")
    val board = AvrBoard.byName(boardName) ?: throw UsbApiException(400, "unknown board: $boardName (supported: ${AvrBoard.names.joinToString()})")
    val hex = b.optString("hex", "")
    if (hex.isEmpty()) throw UsbApiException(400, "hex (Intel HEX text) required")
    val image = try { IntelHex.parse(hex).data } catch (e: IllegalArgumentException) { throw UsbApiException(400, "bad hex: ${e.message}") }
    val portNo = b.optInt("port", 0)
    synchronized(this) { closeDevice(d.deviceName) }
    val port = hub.port(d, portNo)
    val conn = hub.requireOpen(d)
    try { port.open(conn) } catch (e: Exception) { conn.close(); throw UsbApiException(500, "port open failed: ${e.message}") }
    val t0 = System.currentTimeMillis()
    try {
      port.setParameters(board.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
      val link = PortLink(port)
      if (board.protocol == 1) Stk500v1(link, board).upload(image) else Stk500v2(link, board).upload(image)
    } catch (e: FlashException) {
      throw UsbApiException(500, "flash failed: ${e.message}")
    } catch (e: IOException) {
      throw UsbApiException(500, "flash I/O error: ${e.message}")
    } finally {
      try { port.close() } catch (_: Throwable) {}
    }
    return JSONObject().put("ok", true).put("board", board.name).put("bytes", image.size).put("ms", System.currentTimeMillis() - t0)
  }
}
