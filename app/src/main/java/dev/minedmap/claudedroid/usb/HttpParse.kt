package dev.minedmap.claudedroid.usb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

class HttpRequest(
  val method: String,
  val path: String,
  val query: Map<String, String>,
  val headers: Map<String, String>, // lower-case keys
  val body: ByteArray,
)

class HttpError(val status: Int, message: String) : IOException(message)

/** Dependency-free HTTP/1.1 request parser (no keep-alive, no chunked bodies). */
object HttpParse {
  const val MAX_HEADER = 16 * 1024
  const val MAX_BODY = 8 * 1024 * 1024

  fun parseQuery(q: String?): Map<String, String> {
    if (q.isNullOrEmpty()) return emptyMap()
    val m = LinkedHashMap<String, String>()
    for (kv in q.split('&')) {
      if (kv.isEmpty()) continue
      val i = kv.indexOf('=')
      val k = if (i < 0) kv else kv.substring(0, i)
      val v = if (i < 0) "" else kv.substring(i + 1)
      m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
    }
    return m
  }

  fun readRequest(inp: InputStream, maxBody: Int = MAX_BODY): HttpRequest {
    val head = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
      val c = inp.read()
      if (c < 0) throw HttpError(400, "unexpected EOF")
      head.write(c)
      last4 = (last4 shl 8) or c
      if (last4 == 0x0D0A0D0A) break
      if (head.size() > MAX_HEADER) throw HttpError(431, "headers too large")
    }
    val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
    if (lines.isEmpty()) throw HttpError(400, "empty request")
    val rl = lines[0].split(' ')
    if (rl.size < 3) throw HttpError(400, "bad request line")
    val target = rl[1]
    val qi = target.indexOf('?')
    val path = if (qi < 0) target else target.substring(0, qi)
    val query = try { parseQuery(if (qi < 0) null else target.substring(qi + 1)) } catch (e: IllegalArgumentException) {
      throw HttpError(400, "bad query")
    }
    val headers = HashMap<String, String>()
    for (l in lines.drop(1)) {
      val i = l.indexOf(':')
      if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
    }
    if (headers.containsKey("transfer-encoding")) throw HttpError(411, "chunked not supported")
    val len = headers["content-length"]?.toLongOrNull() ?: 0L
    if (len < 0 || len > maxBody) throw HttpError(413, "body too large")
    val body = ByteArray(len.toInt())
    var got = 0
    while (got < body.size) {
      val n = inp.read(body, got, body.size - got)
      if (n < 0) throw HttpError(400, "truncated body")
      got += n
    }
    return HttpRequest(rl[0].uppercase(), path, query, headers, body)
  }

  fun statusText(code: Int) = when (code) {
    200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
    405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
    431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; else -> "Status"
  }

  fun response(code: Int, json: String): ByteArray {
    val body = json.toByteArray(Charsets.UTF_8)
    val h = "HTTP/1.1 $code ${statusText(code)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    return h.toByteArray(Charsets.ISO_8859_1) + body
  }

  /** Constant-time token comparison. Empty expected token never matches. */
  fun tokenOk(expected: String, got: String?): Boolean {
    if (expected.isEmpty() || got == null) return false
    val a = expected.toByteArray(); val b = got.toByteArray()
    return java.security.MessageDigest.isEqual(a, b)
  }
}
