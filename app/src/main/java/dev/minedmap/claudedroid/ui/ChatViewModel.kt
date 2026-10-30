package dev.minedmap.claudedroid.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.minedmap.claudedroid.data.Db
import dev.minedmap.claudedroid.data.MessageEntity
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.data.SessionEntity
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(app: Application, private val sid: Long) : AndroidViewModel(app) {
  private val dao = Db.get(app).dao()
  private val prefs = Prefs(app)
  private val client = BridgeClient(prefs.bridgeUrl, prefs.token)

  val messages: StateFlow<List<MessageEntity>> =
    dao.messages(sid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val session = MutableStateFlow<SessionEntity?>(null)
  val isRunning = MutableStateFlow(false)
  val bridgeUp = MutableStateFlow<Boolean?>(null)
  private var currentReq: String? = null

  init {
    viewModelScope.launch { session.value = dao.session(sid) }
    refreshHealth()
  }

  fun refreshHealth() { viewModelScope.launch { bridgeUp.value = client.health() } }

  private suspend fun add(role: String, text: String) {
    dao.insert(MessageEntity(sessionId = sid, role = role, text = text, ts = System.currentTimeMillis()))
  }

  fun send(text: String) {
    if (text.isBlank()) return
    if (!isRunning.compareAndSet(false, true)) return
    viewModelScope.launch {
      val s0 = dao.session(sid)
      if (s0 == null) { isRunning.value = false; return@launch }
      add("user", text)
      if (s0.title == "새 세션") dao.update(s0.copy(title = text.take(30)))
      var claudeId: String? = s0.claudeSessionId
      val rid = UUID.randomUUID().toString()
      currentReq = rid
      try {
        client.chat(text, claudeId, s0.cwd, rid, prefs.skipPerms).collect { ev ->
          when (ev) {
            is UiEvent.SessionStarted -> if (claudeId == null) {
              claudeId = ev.id
              dao.session(sid)?.let { dao.update(it.copy(claudeSessionId = ev.id)) }
            }
            is UiEvent.AssistantText -> add("assistant", ev.text)
            is UiEvent.ToolUse -> add("tool", "${ev.name}: ${ev.input}")
            is UiEvent.ToolResult -> add("tool_result", ev.text.take(4000))
            is UiEvent.Raw -> add("tool_result", ev.text)
            is UiEvent.Result -> {}
            is UiEvent.Done -> if (ev.code != 0) add("error", ev.stderr.ifBlank { "exit ${ev.code}" })
          }
        }
        bridgeUp.value = true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        add("error", "브리지 연결 실패: ${e.message}")
        bridgeUp.value = false
      } finally {
        withContext(NonCancellable) {
          dao.session(sid)?.let { dao.update(it.copy(updatedAt = System.currentTimeMillis())) }
          session.value = dao.session(sid)
          currentReq = null
          isRunning.value = false
        }
      }
    }
  }

  fun stop() {
    val r = currentReq ?: return
    viewModelScope.launch { client.cancel(r) }
  }

  class Factory(private val app: Application, private val sid: Long) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, sid) as T
  }
}
