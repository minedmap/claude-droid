package dev.minedmap.claudedroid.ui
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.minedmap.claudedroid.data.Db
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.data.SessionEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit, onConnect: () -> Unit) {
  val ctx = LocalContext.current
  val dao = remember { Db.get(ctx).dao() }
  val prefs = remember { Prefs(ctx) }
  val sessions by dao.sessions().collectAsState(initial = emptyList())
  val scope = rememberCoroutineScope()
  var showNew by remember { mutableStateOf(false) }
  var newCwd by remember { mutableStateOf(prefs.defaultCwd) }
  var toDelete by remember { mutableStateOf<SessionEntity?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("Claude Droid") }, actions = {
        IconButton(onClick = onConnect) { Icon(Icons.Filled.Wifi, "연결") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정") }
      })
    },
    floatingActionButton = {
      FloatingActionButton(onClick = { newCwd = prefs.defaultCwd; showNew = true }) { Icon(Icons.Filled.Add, "새 세션") }
    }
  ) { pad ->
    LazyColumn(contentPadding = pad) {
      items(sessions, key = { it.id }) { s ->
        Column(Modifier.fillMaxWidth()
          .combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { toDelete = s })
          .padding(horizontal = 16.dp, vertical = 12.dp)) {
          Text(s.title, style = MaterialTheme.typography.titleMedium)
          Text(s.cwd, style = MaterialTheme.typography.bodySmall)
          Text(DateUtils.getRelativeTimeSpanString(s.updatedAt).toString(), style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider()
      }
    }
  }

  if (showNew) {
    AlertDialog(
      onDismissRequest = { showNew = false },
      title = { Text("새 세션") },
      text = { OutlinedTextField(newCwd, { newCwd = it }, label = { Text("작업 폴더(cwd)") }, singleLine = true) },
      confirmButton = {
        TextButton(onClick = {
          showNew = false
          scope.launch {
            val id = dao.insert(SessionEntity(title = "새 세션", cwd = newCwd.trim().ifEmpty { prefs.defaultCwd }, updatedAt = System.currentTimeMillis()))
            onOpen(id)
          }
        }) { Text("만들기") }
      },
      dismissButton = { TextButton(onClick = { showNew = false }) { Text("취소") } }
    )
  }
  toDelete?.let { s ->
    AlertDialog(
      onDismissRequest = { toDelete = null },
      title = { Text("삭제") },
      text = { Text("대화 기록 삭제됨. Claude 쪽 세션 파일은 남음") },
      confirmButton = { TextButton(onClick = { toDelete = null; scope.launch { dao.delete(s) } }) { Text("삭제") } },
      dismissButton = { TextButton(onClick = { toDelete = null }) { Text("취소") } }
    )
  }
}
