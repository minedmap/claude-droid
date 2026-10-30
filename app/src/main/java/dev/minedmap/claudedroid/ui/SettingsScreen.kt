package dev.minedmap.claudedroid.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.minedmap.claudedroid.data.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onConnect: () -> Unit) {
  val prefs = remember { Prefs(LocalContext.current) }
  var url by remember { mutableStateOf(prefs.bridgeUrl) }
  var token by remember { mutableStateOf(prefs.token) }
  var skip by remember { mutableStateOf(prefs.skipPerms) }
  var cwd by remember { mutableStateOf(prefs.defaultCwd) }

  Scaffold(topBar = {
    TopAppBar(title = { Text("설정") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(url, { url = it; prefs.bridgeUrl = it.trim() }, Modifier.fillMaxWidth(), label = { Text("브리지 URL") }, singleLine = true)
      OutlinedTextField(token, { token = it; prefs.token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("토큰 (붙여넣기)") }, singleLine = true)
      OutlinedTextField(cwd, { cwd = it; prefs.defaultCwd = it.trim() }, Modifier.fillMaxWidth(), label = { Text("기본 cwd") }, singleLine = true)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("권한 확인 건너뛰기 (skipPerms)")
        Switch(skip, { skip = it; prefs.skipPerms = it })
      }
      if (!skip) Text("경고: 권한 필요한 작업에서 멈춤 (-p 모드는 대화형 승인 불가)")
      Button(onClick = onConnect) { Text("연결 마법사") }
    }
  }
}
