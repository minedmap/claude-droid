package dev.minedmap.claudedroid.net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeClient(private val base: String, private val token: String) {
  private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
  private val json = "application/json".toMediaType()

  suspend fun health(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/health").header("X-Token", token).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
  }

  fun chat(prompt: String, sessionId: String?, cwd: String, reqId: String, skipPerms: Boolean): Flow<UiEvent> = callbackFlow {
    val body = JSONObject().put("prompt", prompt).put("sessionId", sessionId ?: JSONObject.NULL)
      .put("cwd", cwd).put("reqId", reqId).put("skipPerms", skipPerms).toString().toRequestBody(json)
    val req = Request.Builder().url("$base/chat").header("X-Token", token).post(body).build()
    val src = EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        StreamParser.parse(data).forEach { trySend(it) }
      }
      override fun onClosed(eventSource: EventSource) { close() }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        close(t ?: IllegalStateException("HTTP ${response?.code}"))
      }
    })
    awaitClose { src.cancel() }
  }

  suspend fun cancel(reqId: String) = withContext(Dispatchers.IO) {
    runCatching {
      http.newCall(Request.Builder().url("$base/cancel").header("X-Token", token)
        .post(JSONObject().put("reqId", reqId).toString().toRequestBody(json)).build()).execute().close()
    }
  }
}
