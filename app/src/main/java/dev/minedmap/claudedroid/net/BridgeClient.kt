package dev.minedmap.claudedroid.net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class FileStat(val exists: Boolean, val size: Long, val isDir: Boolean)
/** One SSE message: [id] = bridge event index (-1 if absent), [events] = parsed UiEvents. */
data class SseMsg(val id: Int, val events: List<UiEvent>)
data class RunInfo(val reqId: String, val done: Boolean, val events: Int)
/** Non-2xx answer from the bridge; message already contains the bridge's {error} text. */
class BridgeHttpException(val code: Int, message: String) : IOException(message)
sealed interface ConnTest {
  object Ok : ConnTest
  object BadToken : ConnTest
  data class Error(val message: String) : ConnTest
}

/** Lightweight handle; every instance shares ONE OkHttpClient (connection + thread pool). */
class BridgeClient(private val base: String, private val token: String) {
  private val http get() = SHARED
  private val json = "application/json".toMediaType()

  companion object {
    private val SHARED: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    const val MAX_UPLOAD = 50L * 1024 * 1024
    /** Always reads current prefs, so URL/token changes in Settings take effect. */
    fun of(p: dev.minedmap.claudedroid.data.Prefs) = BridgeClient(p.bridgeUrl, p.token)
  }

  suspend fun health(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/health").header("X-Token", token).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
  }

  /** null = 브리지 응답 없음 */
  suspend fun adbConnected(): Boolean? = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/adb").header("X-Token", token).build()).execute().use {
        if (!it.isSuccessful) null else JSONObject(it.body!!.string()).optBoolean("connected", false)
      }
    }.getOrNull()
  }

  /** POST /chat. Each SSE event carries `id: N`; the run keeps going on the bridge if we disconnect. */
  fun chat(prompt: String, sessionId: String?, cwd: String, reqId: String, skipPerms: Boolean,
           model: String? = null, effort: String? = null): Flow<SseMsg> {
    val body = JSONObject().put("prompt", prompt).put("sessionId", sessionId ?: JSONObject.NULL)
      .put("cwd", cwd).put("reqId", reqId).put("skipPerms", skipPerms)
      .put("model", model ?: JSONObject.NULL).put("effort", effort ?: JSONObject.NULL).toString().toRequestBody(json)
    return sse(Request.Builder().url("$base/chat").header("X-Token", token).post(body).build())
  }

  /** GET /chat/<reqId>?from=N : replay of events with index >= from, then live until done. */
  fun resume(reqId: String, from: Int): Flow<SseMsg> {
    val url = "$base/chat".toHttpUrl().newBuilder().addPathSegment(reqId).addQueryParameter("from", from.toString()).build()
    return sse(Request.Builder().url(url).header("X-Token", token).get().build())
  }

  private fun sse(req: Request): Flow<SseMsg> = callbackFlow {
    val src = EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        trySend(SseMsg(id?.toIntOrNull() ?: -1, StreamParser.parse(data)))
      }
      override fun onClosed(eventSource: EventSource) { close() }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        if (response != null && !response.isSuccessful) {
          val text = try { response.body?.string() } catch (_: Exception) { null }
          close(BridgeHttpException(response.code, ChatLogic.errorMessage(text, response.code)))
        } else close(t ?: IOException("연결 실패"))
      }
    })
    awaitClose { src.cancel() }
  }.buffer(Channel.UNLIMITED)

  /** GET /runs; null when the bridge is unreachable or answers non-2xx. */
  suspend fun runs(): List<RunInfo>? = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/runs").header("X-Token", token).build()).execute().use { r ->
        if (!r.isSuccessful) return@use null
        val a = JSONObject(r.body!!.string()).optJSONArray("runs") ?: return@use emptyList<RunInfo>()
        (0 until a.length()).mapNotNull { i ->
          val o = a.optJSONObject(i) ?: return@mapNotNull null
          RunInfo(o.optString("reqId"), o.optBoolean("done", false), o.optInt("events", 0))
        }
      }
    }.getOrNull()
  }

  /** GET /health with a human-readable verdict (Settings "연결 테스트"). */
  suspend fun testConnection(): ConnTest = withContext(Dispatchers.IO) {
    try {
      http.newCall(Request.Builder().url("$base/health").header("X-Token", token).build()).execute().use { r ->
        when {
          r.isSuccessful -> ConnTest.Ok
          r.code == 401 -> ConnTest.BadToken
          else -> ConnTest.Error(ChatLogic.errorMessage(r.body?.string(), r.code))
        }
      }
    } catch (e: Exception) {
      ConnTest.Error(e.message ?: e.javaClass.simpleName)
    }
  }

  suspend fun cancel(reqId: String) = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/cancel").header("X-Token", token)
        .post(JSONObject().put("reqId", reqId).toString().toRequestBody(json)).build()).execute().close()
    }
  }

  /** Streams [open]'s data (no full read into memory). [size] < 0 = unknown (chunked). */
  suspend fun upload(cwd: String, name: String, size: Long, open: () -> InputStream): String = withContext(Dispatchers.IO) {
    if (size > MAX_UPLOAD) throw IOException("파일이 너무 큼 (최대 50MB)")
    val body = object : RequestBody() {
      override fun contentType() = "application/octet-stream".toMediaType()
      override fun contentLength() = if (size >= 0) size else -1L
      override fun writeTo(sink: BufferedSink) { open().source().use { sink.writeAll(it) } }
    }
    val url = "$base/upload".toHttpUrl().newBuilder().addQueryParameter("cwd", cwd).addQueryParameter("name", name).build()
    val req = Request.Builder().url(url).header("X-Token", token).post(body).build()
    http.newCall(req).execute().use { r ->
      if (r.code == 413) throw IOException("파일이 너무 큼 (최대 50MB)")
      if (!r.isSuccessful) throw IOException("업로드 실패 HTTP ${r.code}")
      JSONObject(r.body!!.string()).getString("path")
    }
  }

  suspend fun stat(path: String): FileStat? = withContext(Dispatchers.IO) {
    runCatching {
      val url = "$base/stat".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
      http.newCall(Request.Builder().url(url).header("X-Token", token).build()).execute().use { r ->
        if (!r.isSuccessful) return@use null
        val o = JSONObject(r.body!!.string())
        FileStat(o.getBoolean("exists"), o.getLong("size"), o.getBoolean("isDir"))
      }
    }.getOrNull()
  }

  suspend fun fetch(path: String): ByteArray = withContext(Dispatchers.IO) {
    val url = "$base/file".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
    http.newCall(Request.Builder().url(url).header("X-Token", token).build()).execute().use { r ->
      if (!r.isSuccessful) throw IOException(when (r.code) { 403 -> "403 접근 불가 경로"; 404 -> "404 파일 없음"; 413 -> "413 너무 큼"; else -> "HTTP ${r.code}" })
      r.body!!.bytes()
    }
  }
}
