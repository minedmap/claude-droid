package dev.minedmap.claudedroid.net
import org.json.JSONObject

/** Finds file paths Claude produced: tool inputs, assistant text, tool results. */
object FilePathExtractor {
  private val TEXT_RE = Regex("""(?<![\w.:/\-])(/[\w.\-/]+\.(?:png|jpe?g|gif|webp|md|json|txt|csv|log|html|svg|pdf))(?![\w/])""")
  private val WRITE_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
  private val READ_TOOLS = setOf("Read", "Grep", "Glob", "LS", "Bash", "BashOutput", "TodoWrite")
  private val KEYS = listOf("file_path", "path", "output_path", "notebook_path")

  fun fromText(text: String): List<String> =
    TEXT_RE.findAll(text).map { it.groupValues[1] }.distinct().toList()

  /** Write/Edit/NotebookEdit: any file_path. Other (e.g. MCP) tools: file_path/path/output_path with a known extension. Read-only tools are skipped. */
  fun fromToolUse(name: String, inputJson: String): List<String> {
    if (name in READ_TOOLS) return emptyList()
    val o = try { JSONObject(inputJson) } catch (_: Exception) { return emptyList() }
    val writer = name in WRITE_TOOLS
    return KEYS.mapNotNull { k -> o.optString(k, "").takeIf { it.startsWith("/") } }
      .filter { writer || TEXT_RE.matches(it) }
      .distinct()
  }
}
