package dev.minedmap.claudedroid.net
import org.json.JSONObject

/** One-line summary + truncated JSON for tool-call bubbles. */
object ToolSummary {
  const val MAX_INPUT = 4000
  private const val MAX_SUMMARY = 200

  private fun unescape(s: String) = s.replace("\\/", "/")
  private fun oneLine(s: String): String {
    val t = s.trim().replace(Regex("""\s*\n\s*"""), " ")
    return if (t.length > MAX_SUMMARY) t.take(MAX_SUMMARY) + "…" else t
  }

  fun summary(name: String, inputJson: String): String {
    val o = try { JSONObject(inputJson) } catch (_: Exception) { return oneLine(unescape(inputJson)) }
    val keys = when (name) {
      "Bash" -> listOf("command")
      "Read", "Write", "Edit", "MultiEdit" -> listOf("file_path")
      "NotebookEdit" -> listOf("file_path", "notebook_path")
      "Grep", "Glob" -> listOf("pattern")
      else -> emptyList()
    }
    for (k in keys) {
      val v = o.optString(k, "")
      if (v.isNotEmpty()) return oneLine(unescape(v))
    }
    return oneLine(unescape(o.toString()))
  }

  /** Stored text: first line "Name: summary", rest = input JSON truncated to [MAX_INPUT]. */
  fun format(name: String, inputJson: String): String {
    val head = "$name: ${summary(name, inputJson)}"
    val json = unescape(inputJson)
    val body = if (json.length > MAX_INPUT) json.take(MAX_INPUT) + "…" else json
    return if (body == summary(name, inputJson)) head else head + "\n" + body
  }
}
