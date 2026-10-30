package dev.minedmap.claudedroid.net
import org.json.JSONArray
import org.json.JSONObject

sealed interface UiEvent {
  data class SessionStarted(val id: String) : UiEvent
  data class AssistantText(val text: String) : UiEvent
  data class ToolUse(val name: String, val input: String) : UiEvent
  data class ToolResult(val text: String) : UiEvent
  data class Result(val text: String, val costUsd: Double?) : UiEvent
  data class Done(val code: Int, val stderr: String) : UiEvent
  data class Raw(val text: String) : UiEvent
}

object StreamParser {
  fun parse(line: String): List<UiEvent> {
    val o = try { JSONObject(line) } catch (_: Exception) { return listOf(UiEvent.Raw(line)) }
    return when (o.optString("type")) {
      "system" -> if (o.optString("subtype") == "init") listOf(UiEvent.SessionStarted(o.getString("session_id"))) else emptyList()
      "assistant" -> blocks(o).mapNotNull { b ->
        when (b.optString("type")) {
          "text" -> UiEvent.AssistantText(b.getString("text"))
          "tool_use" -> UiEvent.ToolUse(b.getString("name"), b.optJSONObject("input")?.toString() ?: "{}")
          else -> null
        }
      }
      "user" -> blocks(o).filter { it.optString("type") == "tool_result" }.map { b ->
        UiEvent.ToolResult(b.optJSONArray("content")?.let(::joinText) ?: b.optString("content"))
      }
      "result" -> listOf(UiEvent.Result(o.optString("result"),
        if (o.has("total_cost_usd")) o.getDouble("total_cost_usd") else null))
      "done" -> listOf(UiEvent.Done(o.optInt("code"), o.optString("stderr")))
      "raw" -> listOf(UiEvent.Raw(o.optString("text")))
      else -> emptyList()
    }
  }
  private fun blocks(o: JSONObject): List<JSONObject> {
    val a = o.optJSONObject("message")?.optJSONArray("content") ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
  }
  private fun joinText(a: JSONArray) =
    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text") }.joinToString("\n")
}
