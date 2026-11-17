package dev.minedmap.claudedroid.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.usb.HttpError
import dev.minedmap.claudedroid.usb.HttpParse
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Optional, read-only accessibility service. The user must enable it in Settings > Accessibility.
 * Serves GET /a11y/tree on 127.0.0.1:8767 as uiautomator-format XML. Needs the bridge token in X-Token.
 * It performs no actions and reads no events (canRetrieveWindowContent only).
 */
class A11yService : AccessibilityService() {
  companion object {
    const val PORT = 8767
    private const val MAX_NODES = 5000
    private const val MAX_DEPTH = 60
  }

  private var server: ServerSocket? = null
  private val pool = Executors.newCachedThreadPool { r -> Thread(r, "a11y-http").apply { isDaemon = true } }
  @Volatile private var running = false

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
  override fun onInterrupt() {}

  override fun onServiceConnected() {
    super.onServiceConnected()
    if (running) return
    try {
      val s = ServerSocket()
      s.reuseAddress = true
      s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
      server = s; running = true
      Thread({
        while (running) {
          val c = try { s.accept() } catch (_: IOException) { break }
          pool.execute { handle(c) }
        }
      }, "a11y-accept").apply { isDaemon = true; start() }
    } catch (_: Throwable) { /* port busy: the control server just falls back to uiautomator */ }
  }

  override fun onDestroy() {
    running = false
    try { server?.close() } catch (_: Throwable) {}
    server = null
    super.onDestroy()
  }

  private fun handle(c: Socket) = c.use {
    try {
      c.soTimeout = 5_000
      val out = c.getOutputStream()
      val req = try { HttpParse.readRequest(c.getInputStream()) } catch (e: HttpError) {
        out.write(HttpParse.response(e.status, "{\"error\":\"bad request\"}")); return
      }
      val expected = Prefs(this).token
      val (code, body) = when {
        expected.isEmpty() || !HttpParse.tokenOk(expected, req.headers["x-token"]) -> 401 to "{\"error\":\"bad or missing X-Token\"}"
        req.method == "GET" && req.path == "/a11y/health" -> 200 to "{\"ok\":true}"
        req.method == "GET" && req.path == "/a11y/tree" -> tree()
        else -> 404 to "{\"error\":\"not found\"}"
      }
      // /a11y/tree answers raw XML (Content-Type is JSON-flavoured in HttpParse.response; the client only reads the body)
      out.write(HttpParse.response(code, body)); out.flush()
    } catch (_: Throwable) {}
  }

  private fun tree(): Pair<Int, String> {
    val roots = ArrayList<A11yNode>()
    val budget = intArrayOf(MAX_NODES)
    // Only the active window, like uiautomator dump.
    val root = rootInActiveWindow ?: return 503 to "{\"error\":\"no active window\"}"
    try { snap(root, 0, budget)?.let(roots::add) } finally { root.recycle() }
    return 200 to A11yXml.write(roots)
  }

  private fun snap(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): A11yNode? {
    if (budget[0] <= 0 || depth > MAX_DEPTH) return null
    budget[0]--
    val r = Rect(); n.getBoundsInScreen(r)
    val kids = ArrayList<A11yNode>()
    for (i in 0 until n.childCount) {
      val ch = n.getChild(i) ?: continue
      try { if (ch.isVisibleToUser) snap(ch, depth + 1, budget)?.let(kids::add) } finally { ch.recycle() }
    }
    return A11yNode(
      cls = n.className?.toString() ?: "", pkg = n.packageName?.toString() ?: "",
      text = n.text?.toString() ?: "", desc = n.contentDescription?.toString() ?: "",
      id = n.viewIdResourceName ?: "",
      left = r.left, top = r.top, right = r.right, bottom = r.bottom,
      clickable = n.isClickable, longClickable = n.isLongClickable, scrollable = n.isScrollable,
      checkable = n.isCheckable, checked = n.isChecked, focusable = n.isFocusable, focused = n.isFocused,
      selected = n.isSelected, password = n.isPassword, enabled = n.isEnabled, children = kids,
    )
  }
}
