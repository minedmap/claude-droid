package dev.minedmap.claudedroid.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [partial] = streamed text not yet committed as a message; [status] = transient info (never persisted). */
data class RunState(val reqId: String? = null, val partial: String = "", val status: String? = null)

/** Process-wide run state: sessionId -> RunState. Written by ChatService, observed by UI. */
object ChatRuns {
  private val _runs = MutableStateFlow<Map<Long, RunState>>(emptyMap())
  val runs: StateFlow<Map<Long, RunState>> = _runs.asStateFlow()
  /** Last known bridge reachability as seen by the service (null = unknown). HTTP 4xx never sets false. */
  val bridgeUp = MutableStateFlow<Boolean?>(null)
  private val lock = Any()

  /** Atomically marks [sid] as running; false if it already is. */
  fun tryBegin(sid: Long): Boolean = synchronized(lock) {
    if (_runs.value.containsKey(sid)) false else { _runs.value = _runs.value + (sid to RunState()); true }
  }

  fun update(sid: Long, f: (RunState) -> RunState) {
    synchronized(lock) {
      val cur = _runs.value[sid]
      if (cur != null) _runs.value = _runs.value + (sid to f(cur))
    }
  }

  fun end(sid: Long) {
    synchronized(lock) { _runs.value = _runs.value - sid }
  }

  fun isRunning(sid: Long) = _runs.value.containsKey(sid)
}
