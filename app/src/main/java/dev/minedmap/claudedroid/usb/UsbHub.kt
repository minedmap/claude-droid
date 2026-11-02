package dev.minedmap.claudedroid.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class UsbApiException(val status: Int, message: String) : Exception(message)

/** Device enumeration, permission, raw control/bulk transfers. Serial lives in [SerialHub]. */
class UsbHub(ctx: Context) {
  private val app = ctx.applicationContext
  val manager: UsbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
  private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  @Volatile private var registered = false
  var onDetached: ((String) -> Unit)? = null

  companion object {
    const val ACTION_PERMISSION = "dev.minedmap.claudedroid.USB_PERMISSION"

    /** Small VID:PID -> name table for friendlier output. */
    private val KNOWN = mapOf(
      0x2341 to "Arduino", 0x2a03 to "Arduino", 0x1a86 to "WCH (CH34x)", 0x10c4 to "Silicon Labs CP210x",
      0x0403 to "FTDI", 0x067b to "Prolific PL2303", 0x303a to "Espressif",
    )
    private val KNOWN_PID = mapOf(
      0x1a86 to 0x7523 to "CH340 serial", 0x10c4 to 0xea60 to "CP2102 serial", 0x0403 to 0x6001 to "FT232R serial",
      0x2341 to 0x0043 to "Arduino Uno", 0x2341 to 0x0042 to "Arduino Mega 2560", 0x2341 to 0x0001 to "Arduino Uno (old)",
      0x2a03 to 0x0043 to "Arduino Uno (clone id)", 0x303a to 0x1001 to "ESP32-S3/C3 USB-JTAG/serial",
      0x303a to 0x0002 to "ESP32-S2", 0x303a to 0x0009 to "ESP32-S3",
    )

    fun knownName(vid: Int, pid: Int): String? = KNOWN_PID[vid to pid] ?: KNOWN[vid]

    private val prober: UsbSerialProber by lazy {
      val t = UsbSerialProber.getDefaultProbeTable()
      t.addProduct(0x1a86, 0x55d3, CdcAcmSerialDriver::class.java) // CH343
      t.addProduct(0x1a86, 0x55d4, CdcAcmSerialDriver::class.java) // CH9102
      UsbSerialProber(t)
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
      when (i.action) {
        ACTION_PERMISSION -> dev?.let { pending.remove(it.deviceName)?.complete(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) }
        UsbManager.ACTION_USB_DEVICE_DETACHED -> dev?.let { d ->
          pending.remove(d.deviceName)?.complete(false)
          try { onDetached?.invoke(d.deviceName) } catch (_: Throwable) {}
        }
      }
    }
  }

  @Synchronized fun register() {
    if (registered) return
    val f = IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
    ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    registered = true
  }

  @Synchronized fun unregister() {
    if (!registered) return
    try { app.unregisterReceiver(receiver) } catch (_: Throwable) {}
    registered = false
  }

  fun device(id: String?): UsbDevice {
    if (id.isNullOrEmpty()) throw UsbApiException(400, "device required")
    return manager.deviceList[id] ?: throw UsbApiException(404, "device not found: $id")
  }

  fun requireOpen(d: UsbDevice): UsbDeviceConnection {
    if (!manager.hasPermission(d)) throw UsbApiException(403, "no USB permission for ${d.deviceName}; call /usb/permission first")
    return manager.openDevice(d) ?: throw UsbApiException(500, "openDevice failed for ${d.deviceName}")
  }

  fun driverFor(d: UsbDevice): UsbSerialDriver? = try { prober.probeDevice(d) } catch (_: Throwable) { null }

  fun describe(d: UsbDevice): JSONObject {
    val has = manager.hasPermission(d)
    val o = JSONObject()
    o.put("id", d.deviceName)
    o.put("vid", d.vendorId); o.put("pid", d.productId)
    o.put("vidpid", String.format("%04x:%04x", d.vendorId, d.productId))
    knownName(d.vendorId, d.productId)?.let { o.put("knownName", it) }
    o.put("class", d.deviceClass); o.put("subclass", d.deviceSubclass); o.put("protocol", d.deviceProtocol)
    o.put("hasPermission", has)
    if (has) {
      try { o.put("manufacturer", d.manufacturerName ?: JSONObject.NULL) } catch (_: Throwable) {}
      try { o.put("product", d.productName ?: JSONObject.NULL) } catch (_: Throwable) {}
      try { o.put("serial", d.serialNumber ?: JSONObject.NULL) } catch (_: Throwable) {}
    }
    val drv = driverFor(d)
    if (drv != null) {
      o.put("serialDriver", drv.javaClass.simpleName)
      o.put("serialPorts", drv.ports.size)
    }
    val ifs = JSONArray()
    for (n in 0 until d.interfaceCount) {
      val i = d.getInterface(n)
      val io = JSONObject()
      io.put("id", i.id); io.put("alt", i.alternateSetting)
      io.put("class", i.interfaceClass); io.put("subclass", i.interfaceSubclass); io.put("protocol", i.interfaceProtocol)
      val eps = JSONArray()
      for (e in 0 until i.endpointCount) {
        val ep = i.getEndpoint(e)
        eps.put(JSONObject().apply {
          put("address", ep.address)
          put("number", ep.endpointNumber)
          put("type", when (ep.type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            else -> "unknown"
          })
          put("direction", if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out")
          put("maxPacket", ep.maxPacketSize)
          put("interval", ep.interval)
        })
      }
      io.put("endpoints", eps)
      ifs.put(io)
    }
    o.put("interfaces", ifs)
    return o
  }

  fun list(): JSONObject {
    val a = JSONArray()
    for (d in manager.deviceList.values.sortedBy { it.deviceName }) a.put(describe(d))
    return JSONObject().put("devices", a)
  }

  fun requestPermission(d: UsbDevice, timeoutMs: Long = 30_000): JSONObject {
    register()
    if (manager.hasPermission(d)) return JSONObject().put("granted", true).put("already", true)
    val def = CompletableDeferred<Boolean>()
    pending[d.deviceName] = def
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
    manager.requestPermission(d, pi)
    val r = runBlocking { withTimeoutOrNull(timeoutMs) { def.await() } }
    pending.remove(d.deviceName)
    return JSONObject().put("granted", r == true).put("timedOut", r == null)
  }

  fun descriptors(d: UsbDevice): JSONObject {
    val c = requireOpen(d)
    try {
      val raw = c.rawDescriptors ?: throw UsbApiException(500, "no raw descriptors")
      return JSONObject().put("hex", HexUtil.encode(raw)).put("bytes", raw.size)
    } finally { c.close() }
  }

  fun control(d: UsbDevice, b: JSONObject): JSONObject {
    val rt = b.optInt("requestType", -1); val req = b.optInt("request", -1)
    if (rt < 0 || req < 0) throw UsbApiException(400, "requestType and request required")
    val value = b.optInt("value", 0); val index = b.optInt("index", 0)
    val timeout = b.optInt("timeoutMs", 1000).coerceIn(1, 60_000)
    val isIn = (rt and 0x80) != 0
    val buf: ByteArray = if (isIn) ByteArray(b.optInt("length", 0).coerceIn(0, 65535)) else
      (if (b.has("dataHex")) HexUtil.decode(b.getString("dataHex")) else ByteArray(0))
    val c = requireOpen(d)
    try {
      val n = c.controlTransfer(rt, req, value, index, buf, buf.size, timeout)
      if (n < 0) throw UsbApiException(500, "controlTransfer failed ($n)")
      val o = JSONObject().put("transferred", n)
      if (isIn) o.put("dataHex", HexUtil.encode(buf, n))
      return o
    } finally { c.close() }
  }

  fun transfer(d: UsbDevice, b: JSONObject): JSONObject {
    val ifId = b.optInt("interface", -1); val epAddr = b.optInt("endpoint", -1)
    if (ifId < 0 || epAddr < 0) throw UsbApiException(400, "interface and endpoint required")
    val timeout = b.optInt("timeoutMs", 1000).coerceIn(1, 60_000)
    val iface = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id == ifId }
      ?: throw UsbApiException(404, "interface $ifId not found")
    val ep = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull { it.address == epAddr }
      ?: throw UsbApiException(404, "endpoint 0x${Integer.toHexString(epAddr)} not found on interface $ifId")
    val isIn = ep.direction == UsbConstants.USB_DIR_IN
    val buf: ByteArray = if (isIn) ByteArray(b.optInt("length", ep.maxPacketSize).coerceIn(1, 1 shl 20)) else
      (if (b.has("dataHex")) HexUtil.decode(b.getString("dataHex")) else throw UsbApiException(400, "dataHex required for OUT endpoint"))
    val c = requireOpen(d)
    try {
      if (!c.claimInterface(iface, true)) throw UsbApiException(500, "claimInterface failed (interface busy or owned by the system)")
      try {
        val n = c.bulkTransfer(ep, buf, buf.size, timeout)
        if (n < 0) throw UsbApiException(500, "transfer failed or timed out ($n)")
        val o = JSONObject().put("transferred", n)
        if (isIn) o.put("dataHex", HexUtil.encode(buf, n))
        return o
      } finally { c.releaseInterface(iface) }
    } finally { c.close() }
  }

  fun port(d: UsbDevice, portNo: Int): UsbSerialPort {
    val drv = driverFor(d) ?: throw UsbApiException(400, "no serial driver for ${d.deviceName}")
    if (portNo < 0 || portNo >= drv.ports.size) throw UsbApiException(400, "port $portNo out of range (0..${drv.ports.size - 1})")
    return drv.ports[portNo]
  }
}
