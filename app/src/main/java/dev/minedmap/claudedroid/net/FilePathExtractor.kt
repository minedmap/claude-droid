package dev.minedmap.claudedroid.net
import org.json.JSONObject
import java.nio.file.Paths

/** Finds file paths Claude produced: tool inputs, assistant text, tool results. */
object FilePathExtractor {
  const val TERMUX_HOME = "/data/data/com.termux/files/home"
  private const val EXT = "png|jpe?g|gif|webp|bmp|heic|heif|md|json|txt|csv|log|html|svg|pdf"
  private const val IMG = "png|jpe?g|gif|webp|bmp|heic|heif"
  private const val C = """[\p{L}\p{N}_.\-]"""   // unicode-aware (Korean names ok)
  private const val NB = """(?<![\p{L}\p{N}_.:/\-~])"""
  private const val NA = """(?![\p{L}\p{N}_/])"""
  private val ABS_RE = Regex("""$NB((?:/|~/)(?:$C+/)*$C+\.(?:$EXT))$NA""", RegexOption.IGNORE_CASE)
  private val REL_RE = Regex("""$NB((?:\./)?$C+(?:/$C+)+\.(?:$EXT))$NA""", RegexOption.IGNORE_CASE)
  /** quoted/backticked paths may contain spaces */
  private val QUOTED_RE = Regex("""[`"']((?:~/|/|\./)?[^`"'\n]*?\.(?:$EXT))[`"']""", RegexOption.IGNORE_CASE)
  private val IMG_RE = Regex(""".*\.(?:$IMG)""", RegexOption.IGNORE_CASE)
  private val KNOWN_RE = Regex(""".*\.(?:$EXT)""", RegexOption.IGNORE_CASE)
  private val BASH_OUT_RE = Regex(
    """(?:>>?|\s-o|\s--output(?:-file)?=?|\s-O|screencap\s+(?:-p\s+)?)\s*(?:"([^"]+)"|'([^']+)'|([^\s;|&<>]+))""")
  private val WRITE_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
  private val SKIP_TOOLS = setOf("Grep", "Glob", "LS", "BashOutput", "TodoWrite")
  private val KEYS = listOf("file_path", "path", "output_path", "notebook_path")

  /** ~ and relative -> absolute. Relative needs a cwd; returns null when unresolvable. */
  fun resolve(p: String, cwd: String?): String? {
    val home = if (cwd != null && (cwd == "/root" || cwd.startsWith("/root/"))) "/root" else TERMUX_HOME
    val abs = when {
      p.startsWith("/") -> p
      p == "~" || p.startsWith("~/") -> home + p.substring(1)
      cwd != null && cwd.startsWith("/") -> cwd.trimEnd('/') + "/" + p
      else -> return null
    }
    return try { Paths.get(abs).normalize().toString() } catch (_: Exception) { abs }
  }

  fun fromText(text: String, cwd: String? = null): List<String> {
    val raw = ABS_RE.findAll(text).map { it.groupValues[1] } +
      QUOTED_RE.findAll(text).map { it.groupValues[1] }.filter { it.startsWith("/") || it.startsWith("~/") || cwd != null } +
      (if (cwd != null) REL_RE.findAll(text).map { it.groupValues[1] } else emptySequence())
    return raw.filter { "://" !in it }.mapNotNull { resolve(it, cwd) }.distinct().toList()
  }

  /** Write/Edit: any file_path. Read: images only. Bash: output targets (> x, -o x, screencap x).
   *  Other (e.g. MCP) tools: file_path/path/output_path with a known extension. */
  fun fromToolUse(name: String, inputJson: String, cwd: String? = null): List<String> {
    if (name in SKIP_TOOLS) return emptyList()
    val o = try { JSONObject(inputJson) } catch (_: Exception) { return emptyList() }
    if (name == "Bash") {
      val cmd = o.optString("command")
      return BASH_OUT_RE.findAll(cmd).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }
        .filter { KNOWN_RE.matches(it) && !it.startsWith("/dev/") }
        .mapNotNull { resolve(it, cwd) }.distinct().toList()
    }
    if (name == "Read") return listOfNotNull(o.optString("file_path").takeIf { it.startsWith("/") && IMG_RE.matches(it) })
    val writer = name in WRITE_TOOLS
    return KEYS.mapNotNull { k -> o.optString(k, "").takeIf { it.startsWith("/") } }
      .filter { writer || KNOWN_RE.matches(it) }
      .distinct()
  }
}
