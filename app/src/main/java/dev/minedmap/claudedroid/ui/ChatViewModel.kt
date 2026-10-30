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
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.ChatLogic
import dev.minedmap.claudedroid.service.ChatRuns
import dev.minedmap.claudedroid.service.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Attachment(val name: String, val path: String)

class ChatViewModel(app: Application, private val sid: Long) : AndroidViewModel(app) {
  private val dao = Db.get(app).dao()
  private val prefs = Prefs(app)
  private val client get() = BridgeClient.of(prefs)
  /** status polling runs only while the chat screen is started (set by ChatScreen lifecycle) */
  val polling = MutableStateFlow(false)

  val messages: StateFlow<List<MessageEntity>> =
    dao.messages(sid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val session: StateFlow<SessionEntity?> = dao.sessionFlow(sid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val isRunning: StateFlow<Boolean> = ChatRuns.runs.map { sid in it }
    .stateIn(viewModelScope, SharingStarted.Eagerly, ChatRuns.isRunning(sid))
  val partial: StateFlow<String> = ChatRuns.runs.map { it[sid]?.partial ?: "" }
    .stateIn(viewModelScope, SharingStarted.Eagerly, "")
  /** transient status line (e.g. 브리지 자동 시작 중…, 재연결 중…); never stored in DB */
  val status: StateFlow<String?> = ChatRuns.runs.map { it[sid]?.status }
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val bridgeUp = MutableStateFlow<Boolean?>(null)
  val adbUp = MutableStateFlow<Boolean?>(null)
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
        val size = withContext(Dispatchers.IO) {
          runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
              if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
          }.getOrNull() ?: -1L
        }
        val cwd = dao.session(sid)?.cwd ?: prefs.defaultCwd
        val path = client.upload(cwd, name, size) {
          app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("파일을 열 수 없음")
        }
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
    refreshHealth()
    viewModelScope.launch { ChatRuns.bridgeUp.collect { if (it != null) bridgeUp.value = it } }
    viewModelScope.launch { reattachIfNeeded() }
  }

  fun refreshHealth() { viewModelScope.launch { bridgeUp.value = client.health() } }

  init {
    viewModelScope.launch {
      polling.collectLatest { on ->
        while (on) {
          if (!isRunning.value) {
            val a = client.adbConnected()
            bridgeUp.value = a != null
            adbUp.value = a
          }
          kotlinx.coroutines.delay(5000)
        }
      }
    }
  }

  fun send(input: String) {
    val atts = pending.value
    if (input.isBlank() && atts.isEmpty()) return
    if (!ChatRuns.tryBegin(sid)) return
    pending.value = emptyList()
    val text = if (atts.isEmpty()) input
      else input.trimEnd() + "\n\n첨부 파일:\n" + atts.joinToString("\n") { "- ${it.path}" }
    try {
      ChatService.start(getApplication(), sid, text, input.ifBlank { atts.first().name })
    } catch (e: Exception) {
      ChatRuns.end(sid)
      _toast.tryEmit("실행 시작 실패: ${e.message}")
    }
  }

  /** Best effort: after process death, reattach to a run still alive on the bridge. */
  private suspend fun reattachIfNeeded() {
    val ar = prefs.activeRun(sid) ?: return
    if (ChatRuns.isRunning(sid)) return
    val runs = client.runs() ?: return // bridge unreachable: keep the pointer, try again next open
    val info = runs.firstOrNull { it.reqId == ar.reqId }
    val from = ar.lastId + 1
    if (info == null || (info.done && info.events <= from)) { prefs.setActiveRun(sid, null); return }
    if (!ChatRuns.tryBegin(sid)) return
    try {
      ChatService.reattach(getApplication(), sid, ar.reqId, from)
    } catch (e: Exception) {
      ChatRuns.end(sid)
      _toast.tryEmit("재연결 실패: ${e.message}")
    }
  }

  fun setModel(m: String?) {
    val t = m?.trim()?.ifBlank { null }
    if (t != null && !ChatLogic.isValidModel(t)) { _toast.tryEmit("모델 ID 형식 오류"); return }
    viewModelScope.launch { dao.setModel(sid, t) }
  }
  fun setEffort(e: String?) { viewModelScope.launch { dao.setEffort(sid, e) } }

  fun stop() { ChatService.stop(getApplication(), sid) }

  class Factory(private val app: Application, private val sid: Long) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, sid) as T
  }
}
