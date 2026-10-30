package dev.minedmap.claudedroid.ui
import android.app.Application
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sessionId: Long, onBack: () -> Unit, onConnect: () -> Unit) {
  val app = LocalContext.current.applicationContext as Application
  val vm: ChatViewModel = viewModel(key = "chat-$sessionId", factory = ChatViewModel.Factory(app, sessionId))
  val msgs by vm.messages.collectAsState()
  val running by vm.isRunning.collectAsState()
  val up by vm.bridgeUp.collectAsState()
  val session by vm.session.collectAsState()
  val partial by vm.partial.collectAsState()
  var input by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  LaunchedEffect(msgs.size, partial.length / 200) {
    val n = msgs.size + if (partial.isNotEmpty()) 1 else 0
    if (n > 0) listState.scrollToItem(n - 1)
  }

  Scaffold(
    contentWindowInsets = WindowInsets(0),
    topBar = {
      TopAppBar(
        title = { Text(session?.title ?: "채팅", maxLines = 1) },
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
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(msgs, key = { it.id }) { Bubble(it) }
        if (partial.isNotEmpty()) item(key = "partial") { Markdown(content = partial, modifier = Modifier.fillMaxWidth()) }
      }
      Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 6, enabled = !running,
          placeholder = { Text("메시지") })
        IconButton(enabled = !running && input.isNotBlank(), onClick = { vm.send(input); input = "" }) {
          Icon(Icons.AutoMirrored.Filled.Send, "전송")
        }
      }
    }
  }
}

@Composable
private fun Bubble(m: MessageEntity) {
  when (m.role) {
    "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(m.text, Modifier.padding(10.dp))
      }
    }
    "assistant" -> Markdown(content = m.text, modifier = Modifier.fillMaxWidth())
    "error" -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
      Text(m.text, Modifier.fillMaxWidth().padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
    else -> {
      var open by remember { mutableStateOf(false) }
      Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Text(
          (if (m.role == "tool") "🔧 " else "↳ ") + m.text,
          Modifier.padding(8.dp), fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 2)
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
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { custom = false },
      title = { Text("모델 ID") },
      text = { OutlinedTextField(txt, { txt = it }, singleLine = true, placeholder = { Text("claude-opus-5-5") }) },
      confirmButton = { androidx.compose.material3.TextButton(onClick = { onModel(txt); custom = false }) { Text("확인") } },
      dismissButton = { androidx.compose.material3.TextButton(onClick = { custom = false }) { Text("취소") } })
  }
}
