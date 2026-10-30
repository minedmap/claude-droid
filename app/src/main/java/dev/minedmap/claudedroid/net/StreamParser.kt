package dev.minedmap.claudedroid.net
import org.json.JSONArray
import org.json.JSONObject

sealed interface UiEvent {
  data class SessionStarted(val id: String) : UiEvent
  data class AssistantText(val text: String) : UiEvent
  data class ToolUse(val name: String, val input: String) : UiEvent
  data class ToolResult(val text: String, val isError: Boolean = false) : UiEvent
  /** image block inside a tool_result (Read of a png, screenshot MCP, ...) */
  data class ToolImage(val mediaType: String, val base64: String) : UiEvent
  data class Result(val text: String, val costUsd: Double?) : UiEvent
  data class Done(val code: Int, val stderr: String) : UiEvent
  data class Raw(val text: String) : UiEvent
  data class Delta(val text: String) : UiEvent
}

object StreamParser {
  /** Never throws: a malformed line becomes Raw instead of killing the stream. */
  fun parse(line: String): List<UiEvent> = try { parseOrThrow(line) } catch (_: Exception) { listOf(UiEvent.Raw(line)) }

  private fun parseOrThrow(line: String): List<UiEvent> {
    val o = JSONObject(line)
    return when (o.optString("type")) {
      "system" -> {
        val id = o.optString("session_id")
        if (o.optString("subtype") == "init" && id.isNotEmpty()) listOf(UiEvent.SessionStarted(id)) else emptyList()
      }
      "assistant" -> blocks(o).mapNotNull { b ->
        when (b.optString("type")) {
          "text" -> b.optString("text").takeIf { it.isNotEmpty() }?.let { UiEvent.AssistantText(it) }
          "tool_use" -> UiEvent.ToolUse(b.optString("name", "?"), b.optJSONObject("input")?.toString() ?: "{}")
          else -> null
        }
      }
      "user" -> blocks(o).filter { it.optString("type") == "tool_result" }.flatMap(::toolResult)
      "result" -> listOf(UiEvent.Result(o.optString("result"),
        if (o.has("total_cost_usd")) o.optDouble("total_cost_usd").takeIf { !it.isNaN() } else null))
      "stream_event" -> {
        val e = o.optJSONObject("event")
        val d = e?.optJSONObject("delta")
        if (e?.optString("type") == "content_block_delta" && d?.optString("type") == "text_delta")
          listOf(UiEvent.Delta(d.optString("text"))) else emptyList()
      }
      "done" -> listOf(UiEvent.Done(o.optInt("code"), o.optString("stderr")))
      "raw" -> listOf(UiEvent.Raw(o.optString("text")))
      else -> emptyList()
    }
  }

  private fun toolResult(b: JSONObject): List<UiEvent> {
    val err = b.optBoolean("is_error", false)
    val arr = b.optJSONArray("content")
    val texts = mutableListOf<String>()
    val images = mutableListOf<UiEvent>()
    if (arr == null) b.optString("content").takeIf { it.isNotBlank() }?.let { texts += it }
    else for (i in 0 until arr.length()) {
      val c = arr.optJSONObject(i) ?: continue
      if (c.optString("type") == "image") {
        val src = c.optJSONObject("source") ?: continue
        val data = src.optString("data")
        if (src.optString("type", "base64") == "base64" && data.isNotEmpty())
          images += UiEvent.ToolImage(src.optString("media_type", "image/png"), data)
      } else c.optString("text").takeIf { it.isNotBlank() }?.let { texts += it }
    }
    val text = texts.joinToString("\n")
    // empty successful results produce no bubble
    return (if (text.isBlank() && !err) emptyList() else listOf(UiEvent.ToolResult(text, err))) + images
  }

  private fun blocks(o: JSONObject): List<JSONObject> {
    val a: JSONArray = o.optJSONObject("message")?.optJSONArray("content") ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
  }
}
