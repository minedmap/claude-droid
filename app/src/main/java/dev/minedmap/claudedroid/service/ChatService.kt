package dev.minedmap.claudedroid.service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.minedmap.claudedroid.MainActivity
import dev.minedmap.claudedroid.data.ChatDao
import dev.minedmap.claudedroid.data.Db
import dev.minedmap.claudedroid.data.MessageEntity
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.net.ActiveRun
import dev.minedmap.claudedroid.net.BridgeAutoStart
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.BridgeHttpException
import dev.minedmap.claudedroid.net.ChatLogic
import dev.minedmap.claudedroid.net.FilePathExtractor
import dev.minedmap.claudedroid.net.ToolSummary
import dev.minedmap.claudedroid.net.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private class RunPost(val prompt: String, val claudeId: String?, val model: String?, val effort: String?)

/**
 * Owns chat execution: SSE collection, reconnect, Room writes. Runs as a foreground service so the
 * run survives the activity going to background. UI observes [ChatRuns]. All state below is touched
 * on the main thread only (scope uses Dispatchers.Main; blocking work is done inside suspend APIs).
 */
class ChatService : Service() {
  companion object {
    const val ACTION_START = "dev.minedmap.claudedroid.CHAT_START"
    const val ACTION_REATTACH = "dev.minedmap.claudedroid.CHAT_REATTACH"
    const val ACTION_STOP = "dev.minedmap.claudedroid.CHAT_STOP"
    private const val EXTRA_SID = "sid"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_REQ = "req"
    private const val EXTRA_FROM = "from"
    private const val CHANNEL = "chat_runs"
    private const val NOTIF_ID = 4101
    private const val STOP_GRACE_MS = 10_000L

    /** Caller must have done ChatRuns.tryBegin(sid) first. */
    fun start(ctx: Context, sid: Long, text: String, titleSeed: String) {
      val i = Intent(ctx, ChatService::class.java).setAction(ACTION_START)
        .putExtra(EXTRA_SID, sid).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_TITLE, titleSeed)
      ContextCompat.startForegroundService(ctx, i)
    }

    /** Caller must have done ChatRuns.tryBegin(sid) first. [from] = first event index still missing. */
    fun reattach(ctx: Context, sid: Long, reqId: String, from: Int) {
      val i = Intent(ctx, ChatService::class.java).setAction(ACTION_REATTACH)
        .putExtra(EXTRA_SID, sid).putExtra(EXTRA_REQ, reqId).putExtra(EXTRA_FROM, from)
      ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context, sid: Long) {
      try {
        ctx.startService(Intent(ctx, ChatService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SID, sid))
      } catch (_: Exception) {}
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val jobs = HashMap<Long, Job>()
  private val reqIds = HashMap<Long, String>()
  private val stopped = HashSet<Long>()
  private lateinit var dao: ChatDao
  private lateinit var prefs: Prefs
  private var destroyed = false

  override fun onCreate() {
    super.onCreate()
    dao = Db.get(this).dao()
    prefs = Prefs(this)
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Claude 실행", NotificationManager.IMPORTANCE_LOW))
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val i = intent
    if (i == null) { finishIfIdle(); return START_NOT_STICKY }
    when (i.action) {
      ACTION_START, ACTION_REATTACH -> {
        // must be called promptly after startForegroundService()
        promoteToForeground()
        val sid = i.getLongExtra(EXTRA_SID, -1L)
        val text = i.getStringExtra(EXTRA_TEXT)
        val req = i.getStringExtra(EXTRA_REQ)
        val valid = sid >= 0 && ((i.action == ACTION_START && text != null) || (i.action == ACTION_REATTACH && req != null))
        if (!valid) { if (sid >= 0) ChatRuns.end(sid) }
        else if (!jobs.containsKey(sid)) {
          ChatRuns.tryBegin(sid)
          val title = i.getStringExtra(EXTRA_TITLE)
          val from = i.getIntExtra(EXTRA_FROM, 0)
          jobs[sid] = scope.launch {
            try {
              execute(sid, text, title, req, from)
            } finally {
              jobs.remove(sid); reqIds.remove(sid); stopped.remove(sid)
              ChatRuns.end(sid)
              updateNotification()
              finishIfIdle()
            }
          }
          updateNotification()
        }
      }
      ACTION_STOP -> {
        val sid = i.getLongExtra(EXTRA_SID, -1L)
        val targets = if (sid >= 0) listOf(sid) else jobs.keys.toList()
        targets.forEach { stopRun(it) }
      }
      else -> {}
    }
    finishIfIdle()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    destroyed = true
    jobs.keys.toList().forEach { ChatRuns.end(it) }
    scope.cancel()
    super.onDestroy()
  }

  private fun finishIfIdle() {
    if (!destroyed && jobs.isEmpty()) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun stopRun(sid: Long) {
    val job = jobs[sid] ?: return
    stopped.add(sid)
    val rid = reqIds[sid]
    if (rid == null) { job.cancel(); return }
    scope.launch {
      BridgeClient.of(prefs).cancel(rid)
      // bridge normally ends the stream with a done event; if it does not, give up locally
      delay(STOP_GRACE_MS)
      job.cancel()
    }
  }

  // ---- notification ----

  private fun buildNotification(): Notification {
    val open = PendingIntent.getActivity(this, 0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val stopPi = PendingIntent.getService(this, 1,
      Intent(this, ChatService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val n = jobs.size
    val b = Notification.Builder(this, CHANNEL)
      .setContentTitle("Claude 실행 중")
      .setContentText(if (n > 1) "세션 ${n}개 실행 중" else "응답 생성 중…")
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .setContentIntent(open)
      .addAction(Notification.Action.Builder(
        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "중지", stopPi).build())
    if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    return b.build()
  }

  private fun promoteToForeground() {
    val n = buildNotification()
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(NOTIF_ID, n)
  }

  private fun updateNotification() {
    if (jobs.isEmpty()) return
    try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
  }

  // ---- run ----

  private suspend fun addMsg(sid: Long, role: String, text: String) {
    dao.insert(MessageEntity(sessionId = sid, role = role, text = text, ts = System.currentTimeMillis()))
  }

  /** [text] != null: new run (POST /chat). Otherwise reattach to [resumeReq] from event [resumeFrom]. */
  private suspend fun execute(sid: Long, text: String?, titleSeed: String?, resumeReq: String?, resumeFrom: Int) {
    val client = BridgeClient.of(prefs)
    var clearActive = false
    try {
      val s0 = dao.session(sid)
      if (s0 == null) { clearActive = true; return }
      val rc = RunCtx(sid, client, s0.cwd)
      rc.claudeId = s0.claudeSessionId
      if (text != null) {
        addMsg(sid, "user", text)
        if (s0.title == "새 세션" && !titleSeed.isNullOrBlank()) dao.setTitle(sid, titleSeed.take(30))
        if (!client.health()) {
          ChatRuns.update(sid) { it.copy(status = "브리지 자동 시작 중…") }
          ChatRuns.bridgeUp.value = BridgeAutoStart.ensure(this)
          ChatRuns.update(sid) { it.copy(status = null) }
        }
      }
      var fresh = resumeReq == null
      var rid = resumeReq ?: ""
      var from = resumeFrom
      var retriedFresh = false
      while (true) {
        if (fresh) { rid = UUID.randomUUID().toString(); from = 0 }
        val cur = rid
        reqIds[sid] = cur
        ChatRuns.update(sid) { it.copy(reqId = cur) }
        prefs.setActiveRun(sid, ActiveRun(cur, from - 1))
        rc.retryable = text != null && !retriedFresh && rc.claudeId != null
        val post = if (fresh && text != null) {
          val sess = dao.session(sid)
          RunPost(text, rc.claudeId, sess?.model, sess?.effort)
        } else null
        rc.stream(cur, from, post)
        if (rc.noConv && sid !in stopped) {
          retriedFresh = true
          rc.noConv = false
          rc.claudeId = null
          dao.setClaudeSession(sid, null)
          addMsg(sid, "info", "이전 대화를 찾을 수 없어 새 Claude 세션으로 다시 보냅니다")
          fresh = true
          continue
        }
        break
      }
      rc.addFiles(rc.toolPaths.toList()); rc.toolPaths.clear()
      clearActive = !rc.networkFail
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      runCatching { addMsg(sid, "error", "내부 오류: ${e.message}") }
      clearActive = true
    } finally {
      withContext(NonCancellable) {
        val partial = ChatRuns.runs.value[sid]?.partial
        if (!partial.isNullOrEmpty()) runCatching { addMsg(sid, "assistant", partial) }
        if (sid in stopped) runCatching { addMsg(sid, "info", "⏹ 사용자가 중지함") }
        runCatching { dao.touch(sid, System.currentTimeMillis()) }
        if (clearActive || sid in stopped) prefs.setActiveRun(sid, null)
      }
    }
  }

  private inner class RunCtx(val sid: Long, val client: BridgeClient, val cwd: String) {
    var claudeId: String? = null
    var retryable = false
    var noConv = false
    var networkFail = false
    private var lastId = -1
    private var done = false
    private var aborted = false
    private var errorShown = false
    private val seen = mutableSetOf<String>()
    val toolPaths = mutableListOf<String>()

    suspend fun addFiles(paths: List<String>) {
      for (p in paths) {
        if (p in seen || "/.claude-droid/uploads/" in p) continue
        val st = client.stat(p)
        if (st != null && st.exists && !st.isDir) { seen.add(p); addMsg(sid, "file", p) }
      }
    }

    private suspend fun fatal(msg: String, networkDown: Boolean) {
      addMsg(sid, "error", msg)
      errorShown = true
      aborted = true
      if (networkDown) { networkFail = true; ChatRuns.bridgeUp.value = false }
    }

    /** One logical stream: POST (if [post]) then GET-resume with backoff until a done event arrives. */
    suspend fun stream(rid: String, startFrom: Int, post: RunPost?) {
      lastId = startFrom - 1
      done = false; aborted = false; noConv = false; networkFail = false
      var usePost = post != null
      var gotMsg = false
      var attempt = 0
      var reconnecting = false

      suspend fun transient(msg: String?) {
        attempt++
        if (attempt > ChatLogic.RECONNECT_MAX) {
          fatal("브리지 연결 실패: ${msg ?: "알 수 없음"}", true)
        } else {
          reconnecting = true
          ChatRuns.update(sid) { it.copy(status = "재연결 중… ($attempt/${ChatLogic.RECONNECT_MAX})") }
          delay(ChatLogic.backoffMs(attempt - 1))
        }
      }

      while (!done && !aborted) {
        val flow = if (usePost && post != null)
          client.chat(post.prompt, post.claudeId, cwd, rid, prefs.skipPerms, post.model, post.effort)
        else client.resume(rid, lastId + 1)
        try {
          flow.collect { m ->
            gotMsg = true; usePost = false
            if (reconnecting) {
              reconnecting = false; attempt = 0
              ChatRuns.update(sid) { it.copy(status = null) }
            }
            if (m.id in 0..lastId) return@collect // already handled (replay overlap)
            for (ev in m.events) handle(ev)
            if (m.id > lastId) { lastId = m.id; prefs.setActiveRun(sid, ActiveRun(rid, lastId)) }
          }
          if (!done) throw IOException("스트림이 중간에 끊김")
          ChatRuns.bridgeUp.value = true
        } catch (e: CancellationException) {
          throw e
        } catch (e: BridgeHttpException) {
          // HTTP answers (4xx incl.) mean the bridge is up: never flip bridgeUp to false here
          if (e.code >= 500 && !usePost) transient(e.message)
          else fatal(if (usePost) (e.message ?: "HTTP ${e.code}") else "재연결 실패: ${e.message}", false)
        } catch (e: Exception) {
          if (usePost && !gotMsg) fatal("브리지 연결 실패: ${e.message ?: e.javaClass.simpleName}", true)
          else transient(e.message ?: e.javaClass.simpleName)
        }
      }
    }

    private suspend fun handle(ev: UiEvent) {
      when (ev) {
        is UiEvent.SessionStarted -> if (ev.id != claudeId) {
          claudeId = ev.id
          dao.setClaudeSession(sid, ev.id)
        }
        is UiEvent.Delta -> ChatRuns.update(sid) { it.copy(partial = it.partial + ev.text) }
        is UiEvent.AssistantText -> {
          ChatRuns.update(sid) { it.copy(partial = "") }
          addMsg(sid, "assistant", ev.text)
          addFiles(FilePathExtractor.fromText(ev.text, cwd))
        }
        is UiEvent.ToolUse -> {
          addMsg(sid, "tool", ToolSummary.format(ev.name, ev.input))
          toolPaths += FilePathExtractor.fromToolUse(ev.name, ev.input, cwd)
        }
        is UiEvent.ToolResult -> {
          if (ev.text.isNotBlank() || ev.isError)
            addMsg(sid, if (ev.isError) "tool_error" else "tool_result", ev.text.ifBlank { "(오류)" }.take(4000))
          val ps = toolPaths.toList(); toolPaths.clear()
          addFiles(ps + FilePathExtractor.fromText(ev.text, cwd))
        }
        is UiEvent.ToolImage -> saveImage(ev)?.let { addMsg(sid, "image", it) }
        is UiEvent.Raw -> if (ev.text.isNotBlank()) addMsg(sid, "tool_result", ev.text)
        is UiEvent.Result -> {
          if (ev.isError) {
            if (retryable && ChatLogic.isNoConversation(ev.text)) noConv = true
            else { addMsg(sid, "error", ev.text.ifBlank { "오류" }); errorShown = true }
          }
          ChatLogic.costLabel(ev.costUsd)?.let { addMsg(sid, "tool_result", it) }
        }
        is UiEvent.Done -> {
          done = true
          if (retryable && ev.code != 0 && ChatLogic.isNoConversation(ev.stderr)) noConv = true
          if (!noConv) {
            ChatLogic.doneError(ev.code, ev.stderr, errorShown, sid in stopped)?.let { addMsg(sid, "error", it); errorShown = true }
          }
        }
      }
    }

    /** Decode to filesDir/images; DB stores only the local path (base64 rows could exceed Room's cursor window). */
    private suspend fun saveImage(ev: UiEvent.ToolImage): String? = withContext(Dispatchers.IO) {
      runCatching {
        val bytes = android.util.Base64.decode(ev.base64, android.util.Base64.DEFAULT)
        val ext = when (ev.mediaType.substringAfter('/').lowercase()) {
          "jpeg" -> "jpg"; "" -> "png"; else -> ev.mediaType.substringAfter('/').lowercase().filter { it.isLetterOrDigit() }
        }
        val dir = java.io.File(filesDir, "images").apply { mkdirs() }
        java.io.File(dir, "${UUID.randomUUID()}.$ext").apply { writeBytes(bytes) }.absolutePath
      }.getOrNull()
    }
  }
}
