package dev.minedmap.claudedroid.ui
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.m3.Markdown
import dev.minedmap.claudedroid.data.MessageEntity
import dev.minedmap.claudedroid.net.ChatLogic
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sessionId: Long, onBack: () -> Unit, onConnect: () -> Unit) {
  val app = LocalContext.current.applicationContext as Application
  val vm: ChatViewModel = viewModel(key = "chat-$sessionId", factory = ChatViewModel.Factory(app, sessionId))
  @Suppress("DEPRECATION")
  val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
  androidx.compose.runtime.DisposableEffect(owner) {
    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
      when (e) {
        androidx.lifecycle.Lifecycle.Event.ON_START -> vm.polling.value = true
        androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.polling.value = false
        else -> {}
      }
    }
    owner.lifecycle.addObserver(obs)
    onDispose { owner.lifecycle.removeObserver(obs); vm.polling.value = false }
  }
  val msgs by vm.messages.collectAsState()
  val running by vm.isRunning.collectAsState()
  val up by vm.bridgeUp.collectAsState()
  val adb by vm.adbUp.collectAsState()
  val session by vm.session.collectAsState()
  val partial by vm.partial.collectAsState()
  val status by vm.status.collectAsState()
  var input by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val ctx = LocalContext.current
  val pending by vm.pending.collectAsState()
  val uploading by vm.uploading.collectAsState()
  var attachMenu by remember { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    uris.forEach { vm.attach(it) }
  }
  LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

  val uiScope = rememberCoroutineScope()
  // near-bottom is sampled whenever scrolling goes idle, so a burst of new items cannot make us "lose" the bottom
  val nearBottom by remember {
    derivedStateOf {
      val li = listState.layoutInfo
      val last = li.visibleItemsInfo.lastOrNull()
      li.totalItemsCount == 0 || (last != null && last.index >= li.totalItemsCount - 2)
    }
  }
  var stick by remember { mutableStateOf(true) }
  var hasNew by remember { mutableStateOf(false) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
      stick = nearBottom
      if (stick) hasNew = false
    }
  }
  val itemCount = msgs.size + if (partial.isNotEmpty()) 1 else 0
  LaunchedEffect(msgs.size, partial.length / 200) {
    if (itemCount > 0) {
      if (stick) listState.scrollToItem(itemCount - 1) else hasNew = true
    }
  }

  Scaffold(
    contentWindowInsets = WindowInsets(0),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(session?.title ?: "채팅", maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              StatusDot("브리지", up)
              StatusDot("adb", if (up == false) null else adb)
            }
          }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        actions = {
          if (running) IconButton(onClick = { vm.stop() }) { Icon(Icons.Filled.Stop, "중지") }
        }
      )
    }
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).imePadding().navigationBarsPadding()) {
      if (up == false) {
        Box(Modifier.fillMaxWidth().background(Color(0xFFC62828)).clickable { onConnect() }.padding(10.dp)) {
          Text("브리지 꺼짐 — 탭하여 연결", color = Color.White)
        }
      }
      ModelBar(session?.model, session?.effort, !running, vm::setModel, vm::setEffort)
      Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState,
          verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
          items(msgs, key = { it.id }) { Bubble(it) }
          if (partial.isNotEmpty()) item(key = "partial") { Markdown(content = partial, modifier = Modifier.fillMaxWidth()) }
        }
        if (!stick && hasNew) {
          FilledTonalButton(
            onClick = {
              uiScope.launch {
                stick = true; hasNew = false
                if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
              }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
          ) { Text("↓ 새 메시지") }
        }
      }
      status?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary) }
      if (pending.isNotEmpty() || uploading > 0) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          pending.forEach { a ->
            InputChip(selected = false, onClick = { vm.removeAttachment(a) }, label = { Text(a.name, maxLines = 1) },
              trailingIcon = { Icon(Icons.Filled.Close, "제거", Modifier.size(16.dp)) })
          }
          if (uploading > 0) Text("업로드 중… ($uploading)", style = MaterialTheme.typography.bodySmall)
        }
      }
      Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        Box {
          IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.AttachFile, "첨부") }
          DropdownMenu(attachMenu, { attachMenu = false }) {
            DropdownMenuItem(text = { Text("파일 선택") }, onClick = { attachMenu = false; picker.launch(arrayOf("*/*")) })
            DropdownMenuItem(text = { Text("[클립보드 이미지 붙여넣기]") }, onClick = { attachMenu = false; pasteClipboardImage(ctx, vm) })
          }
        }
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 6,
          placeholder = { Text("메시지") })
        IconButton(enabled = !running && uploading == 0 && (input.isNotBlank() || pending.isNotEmpty()), onClick = { vm.send(input); input = "" }) {
          Icon(Icons.AutoMirrored.Filled.Send, "전송")
        }
      }
    }
  }
}

private fun copyText(ctx: Context, text: String) {
  val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText("claude-droid", text))
  Toast.makeText(ctx, "복사됨", Toast.LENGTH_SHORT).show()
}

private fun pasteClipboardImage(ctx: Context, vm: ChatViewModel) {
  val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip = cm.primaryClip
  val uri = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).uri else null
  val mime = uri?.let { runCatching { ctx.contentResolver.getType(it) }.getOrNull() }
  if (uri == null || mime == null || !mime.startsWith("image/")) {
    Toast.makeText(ctx, "클립보드에 이미지 없음", Toast.LENGTH_SHORT).show(); return
  }
  val ext = when (val e = mime.substringAfter('/')) { "jpeg" -> "jpg"; "" -> "png"; else -> e.filter { it.isLetterOrDigit() } }
  vm.attach(uri, "clip-${System.currentTimeMillis()}.$ext")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: MessageEntity) {
  val ctx = LocalContext.current
  val copy = { copyText(ctx, m.text) }
  when (m.role) {
    "file" -> FileCard(m.text)
    "image" -> LocalImageCard(m.text)
    "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = copy)) {
        Text(m.text, Modifier.padding(10.dp))
      }
    }
    "assistant" -> Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = copy)) {
      SelectionContainer { Markdown(content = m.text, modifier = Modifier.fillMaxWidth()) }
      IconButton(onClick = copy, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.ContentCopy, "복사", Modifier.size(16.dp)) }
    }
    "info" -> Text("ℹ " + m.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    "error" -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer,
      modifier = Modifier.combinedClickable(onClick = {}, onLongClick = copy)) {
      Text(m.text, Modifier.fillMaxWidth().padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
    else -> {
      var open by remember { mutableStateOf(false) }
      val isErr = m.role == "tool_error"
      Surface(shape = RoundedCornerShape(8.dp),
        color = if (isErr) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { open = !open }, onLongClick = copy)) {
        SelectionContainer {
          Text(
            (when (m.role) { "tool" -> "🔧 "; "tool_error" -> "⚠ "; else -> "↳ " }) +
              // tool rows: first line = summary, rest = (truncated) input JSON shown only when expanded
              (if (m.role == "tool" && !open) m.text.substringBefore('\n') else m.text),
            Modifier.padding(8.dp), color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else if (m.role == "tool") 1 else 2)
        }
      }
    }
  }
}

private val MODELS = listOf(null, "opus", "sonnet", "haiku", "fable")
private val EFFORTS = listOf(null, "low", "medium", "high", "xhigh", "max")

@Composable
private fun ModelBar(model: String?, effort: String?, enabled: Boolean,
                     onModel: (String?) -> Unit, onEffort: (String?) -> Unit) {
  var mOpen by remember { mutableStateOf(false) }
  var eOpen by remember { mutableStateOf(false) }
  var custom by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box {
      androidx.compose.material3.AssistChip(enabled = enabled, onClick = { mOpen = true },
        label = { Text("모델: " + (model ?: "기본")) })
      androidx.compose.material3.DropdownMenu(mOpen, { mOpen = false }) {
        MODELS.forEach { m ->
          androidx.compose.material3.DropdownMenuItem(text = { Text(m ?: "기본") },
            onClick = { onModel(m); mOpen = false })
        }
        androidx.compose.material3.DropdownMenuItem(text = { Text("직접 입력…") },
          onClick = { custom = true; mOpen = false })
      }
    }
    Box {
      androidx.compose.material3.AssistChip(enabled = enabled, onClick = { eOpen = true },
        label = { Text("effort: " + (effort ?: "기본")) })
      androidx.compose.material3.DropdownMenu(eOpen, { eOpen = false }) {
        EFFORTS.forEach { e ->
          androidx.compose.material3.DropdownMenuItem(text = { Text(e ?: "기본") },
            onClick = { onEffort(e); eOpen = false })
        }
      }
    }
  }
  if (custom) {
    var txt by remember { mutableStateOf(model ?: "") }
    var modelErr by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { custom = false },
      title = { Text("모델 ID") },
      text = {
        OutlinedTextField(txt, { txt = it; modelErr = null }, singleLine = true, isError = modelErr != null,
          placeholder = { Text("claude-opus-5-5") },
          supportingText = { modelErr?.let { Text(it) } })
      },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = {
          val t = txt.trim()
          when {
            t.isEmpty() -> { onModel(null); custom = false }
            ChatLogic.isValidModel(t) -> { onModel(t); custom = false }
            else -> modelErr = "영문/숫자/. _ - [ ] 만, 최대 64자"
          }
        }) { Text("확인") }
      },
      dismissButton = { androidx.compose.material3.TextButton(onClick = { custom = false }) { Text("취소") } })
  }
}

@Composable
private fun StatusDot(label: String, ok: Boolean?) {
  val c = when (ok) { true -> Color(0xFF2E7D32); false -> Color(0xFFC62828); null -> Color.Gray }
  Text("● $label", color = c, style = MaterialTheme.typography.labelSmall)
}
