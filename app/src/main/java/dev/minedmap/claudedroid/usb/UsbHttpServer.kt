package dev.minedmap.claudedroid.usb

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Dependency-free loopback HTTP API. Every request needs the shared bridge token in X-Token. */
class UsbHttpServer(
  private val hub: UsbHub,
  private val serial: SerialHub,
  private val tokenProvider: () -> String,
  private val port: Int = DEFAULT_PORT,
) {
  companion object { const val DEFAULT_PORT = 8766 }

  private var server: ServerSocket? = null
  private val pool = Executors.newCachedThreadPool { r -> Thread(r, "usb-http").apply { isDaemon = true } }
  @Volatile private var running = false

  val boundPort get() = server?.localPort ?: -1

  @Synchronized fun start() {
    if (running) return
    val s = ServerSocket()
    s.reuseAddress = true
    s.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    server = s
    running = true
    Thread({
      while (running) {
        val c = try { s.accept() } catch (_: IOException) { break }
        pool.execute { handle(c) }
      }
    }, "usb-http-accept").apply { isDaemon = true; start() }
  }

  @Synchronized fun stop() {
    running = false
    try { server?.close() } catch (_: Throwable) {}
    server = null
  }

  private fun handle(c: Socket) {
    c.use {
      try {
        c.soTimeout = 15_000
        val req = try { HttpParse.readRequest(c.getInputStream()) } catch (e: HttpError) {
          c.getOutputStream().write(HttpParse.response(e.status, err(e.message))); return
        }
        c.soTimeout = 0
        val (code, text) = route(req)
        c.getOutputStream().write(HttpParse.response(code, text))
        c.getOutputStream().flush()
      } catch (_: IOException) {
      } catch (e: Throwable) {
        try { c.getOutputStream().write(HttpParse.response(500, err(e.message ?: e.javaClass.simpleName))) } catch (_: Throwable) {}
      }
    }
  }

  private fun err(m: String?) = JSONObject().put("error", m ?: "error").toString()

  /** Auth + routing; returns status + JSON text. */
  internal fun route(req: HttpRequest): Pair<Int, String> {
    if (!HttpParse.tokenOk(tokenProvider(), req.headers["x-token"])) return 401 to err("bad or missing X-Token")
    return try {
      val j: JSONObject = when {
        req.method == "GET" && req.path == "/usb/health" ->
          JSONObject().put("ok", true).put("version", "0.6.0").put("devices", hub.manager.deviceList.size)
        req.method == "GET" && req.path == "/usb/devices" -> hub.list()
        req.method == "GET" && req.path == "/usb/descriptors" -> hub.descriptors(hub.device(req.query["device"]))
        req.method == "GET" && req.path == "/serial/read" -> serial.read(req.query)
        req.method == "POST" -> {
          val b = body(req)
          when (req.path) {
            "/usb/permission" -> hub.requestPermission(hub.device(b.optString("device", "")))
            "/usb/control" -> hub.control(hub.device(b.optString("device", "")), b)
            "/usb/transfer" -> hub.transfer(hub.device(b.optString("device", "")), b)
            "/serial/open" -> serial.open(b)
            "/serial/write" -> serial.write(b)
            "/serial/lines" -> serial.lines(b)
            "/serial/close" -> serial.close(b)
            "/serial/tcp" -> serial.tcp(b)
            "/flash/avr" -> serial.flashAvr(b)
            else -> throw UsbApiException(404, "not found: ${req.path}")
          }
        }
        else -> throw UsbApiException(404, "not found: ${req.method} ${req.path}")
      }
      200 to j.toString()
    } catch (e: UsbApiException) {
      e.status to err(e.message)
    } catch (e: IllegalArgumentException) {
      400 to err(e.message)
    } catch (e: JSONException) {
      400 to err("bad json: ${e.message}")
    } catch (e: SecurityException) {
      403 to err(e.message)
    }
  }

  private fun body(req: HttpRequest): JSONObject =
    if (req.body.isEmpty()) JSONObject() else JSONObject(String(req.body, Charsets.UTF_8))
}
