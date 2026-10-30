package dev.minedmap.claudedroid.net
import org.json.JSONObject
import java.util.Locale

/** Persisted pointer to a bridge run so the app can reattach after process death. */
data class ActiveRun(val reqId: String, val lastId: Int) {
  companion object {
    fun encode(r: ActiveRun) = "${r.reqId}|${r.lastId}"
    fun decode(s: String?): ActiveRun? {
      if (s.isNullOrEmpty()) return null
      val i = s.lastIndexOf('|')
      if (i <= 0) return null
      val last = s.substring(i + 1).toIntOrNull() ?: return null
      return ActiveRun(s.substring(0, i), last)
    }
  }
}

/** Pure decision helpers shared by ChatService / UI (JVM-testable). */
object ChatLogic {
  private val MODEL_RE = Regex("""^[A-Za-z0-9._\-\[\]]{1,64}$""")
  const val NO_CONVERSATION = "No conversation found"
  const val RECONNECT_MAX = 5

  fun isValidModel(s: String): Boolean = MODEL_RE.matches(s)

  fun isNoConversation(vararg texts: String?): Boolean =
    texts.any { it?.contains(NO_CONVERSATION, ignoreCase = true) == true }

  /** null when cost is missing / zero / not a number. */
  fun costLabel(c: Double?): String? =
    if (c == null || c.isNaN() || c <= 0.0) null else "💰 $" + String.format(Locale.US, "%.4f", c)

  /** Text for the extra error bubble on a done event, or null when none should be shown. */
  fun doneError(code: Int, stderr: String, errorShown: Boolean, userStopped: Boolean): String? =
    if (code == 0 || errorShown || userStopped) null else stderr.ifBlank { "exit $code" }

  /** Extracts {error} from a bridge JSON body; falls back to "HTTP code". */
  fun errorMessage(body: String?, code: Int): String {
    val e = try { if (body.isNullOrBlank()) null else JSONObject(body).optString("error", "").ifBlank { null } } catch (_: Exception) { null }
    return if (e != null) "$e (HTTP $code)" else "HTTP $code"
  }

  fun backoffMs(attempt: Int): Long = minOf(1000L shl attempt.coerceIn(0, 10), 8000L)
}
