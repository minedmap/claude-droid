package dev.minedmap.claudedroid.net
import org.json.JSONObject
import java.time.OffsetDateTime

/** Subscription usage from bridge GET /usage: session (5h) and weekly utilization, in percent. */
data class UsageInfo(val sessionPct: Double?, val sessionResetMs: Long?, val weeklyPct: Double?, val weeklyResetMs: Long?) {
  companion object {
    fun parse(body: String): UsageInfo? = try {
      val o = JSONObject(body)
      if (o.has("error")) null else {
        val s = o.optJSONObject("session"); val w = o.optJSONObject("weekly")
        fun pct(x: JSONObject?) = if (x != null && x.has("pct") && !x.isNull("pct")) x.optDouble("pct").takeIf { !it.isNaN() } else null
        fun ms(x: JSONObject?) = x?.optString("resetsAt", "")?.takeIf { it.isNotEmpty() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        val r = UsageInfo(pct(s), ms(s), pct(w), ms(w))
        if (r.sessionPct == null && r.weeklyPct == null) null else r
      }
    } catch (_: Exception) { null }
  }
}
