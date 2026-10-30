package dev.minedmap.claudedroid.ui
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.minedmap.claudedroid.data.Db
import dev.minedmap.claudedroid.data.MessageEntity
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.data.SessionEntity
import dev.minedmap.claudedroid.net.BridgeAutoStart
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.FilePathExtractor
import dev.minedmap.claudedroid.net.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class Attachment(val name: String, val path: String)

class ChatViewModel(app: Application, private val sid: Long) : AndroidViewModel(app) {
  private val dao = Db.get(app).dao()
  private val prefs = Prefs(app)
  private val client = BridgeClient(prefs.bridgeUrl, prefs.token)

  val messages: StateFlow<List<MessageEntity>> =
    dao.messages(sid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val session = MutableStateFlow<SessionEntity?>(null)
  val isRunning = MutableStateFlow(false)
  val partial = MutableStateFlow("")
  val bridgeUp = MutableStateFlow<Boolean?>(null)
  private var currentReq: String? = null
  val pending = MutableStateFlow<List<Attachment>>(emptyList())
  val uploading = MutableStateFlow(0)
  private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val toast = _toast.asSharedFlow()

  fun attach(uri: Uri, nameOverride: String? = null) {
    uploading.value += 1
    viewModelScope.launch {
      try {
        val app = getApplication<Application>()
        val name = nameOverride ?: withContext(Dispatchers.IO) {
          runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
              if (c.moveToFirst()) c.getString(0) else null
            }
          }.getOrNull()
        } ?: uri.lastPathSegment ?: "upload.bin"
        val cwd = dao.session(sid)?.cwd ?: prefs.defaultCwd
        val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("파일을 열 수 없음")
        val path = client.upload(cwd, name, input)
        pending.value = pending.value + Attachment(path.substringAfterLast('/'), path)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _toast.tryEmit("첨부 실패: ${e.message}")
      } finally {
        uploading.value -= 1
      }
    }
  }

  fun removeAttachment(a: Attachment) { pending.value = pending.value - a }

  init {
    viewModelScope.launch { session.value = dao.session(sid) }
    refreshHealth()
  }

  fun refreshHealth() { viewModelScope.launch { bridgeUp.value = client.health() } }

  private suspend fun add(role: String, text: String) {
    dao.insert(MessageEntity(sessionId = sid, role = role, text = text, ts = System.currentTimeMillis()))
  }

  fun send(input: String) {
    val atts = pending.value
    if (input.isBlank() && atts.isEmpty()) return
    if (!isRunning.compareAndSet(false, true)) return
    pending.value = emptyList()
    val text = if (atts.isEmpty()) input
      else input.trimEnd() + "\n\n첨부 파일:\n" + atts.joinToString("\n") { "- ${it.path}" }
    viewModelScope.launch {
      val s0 = dao.session(sid)
      if (s0 == null) { isRunning.value = false; return@launch }
      add("user", text)
      if (s0.title == "새 세션") dao.update(s0.copy(title = (input.ifBlank { atts.first().name }).take(30)))
      if (!client.health()) {
        add("tool_result", "브리지 자동 시작 중…")
        bridgeUp.value = BridgeAutoStart.ensure(getApplication())
      }
      var claudeId: String? = s0.claudeSessionId
      val rid = UUID.randomUUID().toString()
      currentReq = rid
      val seen = mutableSetOf<String>()
      val toolPaths = mutableListOf<String>()
      suspend fun addFiles(paths: List<String>) {
        for (p in paths) {
          if (p in seen || "/.claude-droid/uploads/" in p) continue
          val st = client.stat(p)
          if (st != null && st.exists && !st.isDir) { seen.add(p); add("file", p) }
        }
      }
      try {
        client.chat(text, claudeId, s0.cwd, rid, prefs.skipPerms, s0.model, s0.effort).collect { ev ->
          when (ev) {
            is UiEvent.SessionStarted -> if (claudeId == null) {
              claudeId = ev.id
              dao.session(sid)?.let { dao.update(it.copy(claudeSessionId = ev.id)) }
            }
            is UiEvent.Delta -> partial.value += ev.text
            is UiEvent.AssistantText -> { partial.value = ""; add("assistant", ev.text); addFiles(FilePathExtractor.fromText(ev.text)) }
            is UiEvent.ToolUse -> {
              add("tool", "${ev.name}: ${ev.input}")
              toolPaths += FilePathExtractor.fromToolUse(ev.name, ev.input)
            }
            is UiEvent.ToolResult -> {
              add("tool_result", ev.text.take(4000))
              val ps = toolPaths.toList(); toolPaths.clear()
              addFiles(ps + FilePathExtractor.fromText(ev.text))
            }
            is UiEvent.Raw -> add("tool_result", ev.text)
            is UiEvent.Result -> ev.costUsd?.let { add("tool_result", "💰 $" + String.format(java.util.Locale.US, "%.4f", it)) }
            is UiEvent.Done -> if (ev.code != 0) add("error", ev.stderr.ifBlank { "exit ${ev.code}" })
          }
        }
        addFiles(toolPaths.toList()); toolPaths.clear()
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
          if (partial.value.isNotEmpty()) { add("assistant", partial.value); partial.value = "" }
          isRunning.value = false
        }
      }
    }
  }

  fun setModel(m: String?) = updateSession { it.copy(model = m?.trim()?.ifBlank { null }) }
  fun setEffort(e: String?) = updateSession { it.copy(effort = e) }
  private fun updateSession(f: (SessionEntity) -> SessionEntity) {
    viewModelScope.launch { dao.session(sid)?.let { dao.update(f(it)); session.value = dao.session(sid) } }
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
