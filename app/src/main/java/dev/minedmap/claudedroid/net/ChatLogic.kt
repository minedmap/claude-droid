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

  fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
  }

  /** null when usage is missing or all zero. Cache = read + write. */
  fun tokenLabel(u: UiEvent.TokenUsage?): String? {
    if (u == null || (u.input + u.output + u.cacheRead + u.cacheWrite) <= 0L) return null
    return "🔢 입력 ${fmtTokens(u.input)} · 출력 ${fmtTokens(u.output)} · 캐시 ${fmtTokens(u.cacheRead + u.cacheWrite)}"
  }

  /** "2h 17m" / "45m" / "3d 4h"; null when past or unparsable. */
  fun resetIn(resetsAtMs: Long?, nowMs: Long): String? {
    if (resetsAtMs == null) return null
    val m = (resetsAtMs - nowMs) / 60_000
    if (m <= 0) return null
    return when {
      m >= 1440 -> "${m / 1440}d ${(m % 1440) / 60}h"
      m >= 60 -> "${m / 60}h ${m % 60}m"
      else -> "${m}m"
    }
  }

  fun usageLabel(u: UsageInfo?, nowMs: Long): String? {
    if (u == null) return null
    val parts = mutableListOf<String>()
    u.sessionPct?.let { p ->
      parts += "세션 ${Math.round(p)}%" + (resetIn(u.sessionResetMs, nowMs)?.let { " ($it)" } ?: "")
    }
    u.weeklyPct?.let { p ->
      parts += "주간 ${Math.round(p)}%" + (resetIn(u.weeklyResetMs, nowMs)?.let { " ($it)" } ?: "")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

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
