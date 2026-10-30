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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.minedmap.claudedroid.net.BridgeClient
import dev.minedmap.claudedroid.net.ConnTest
import kotlinx.coroutines.launch
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
  val ctx = LocalContext.current
  val prefs = remember { Prefs(ctx) }
  var url by remember { mutableStateOf(prefs.bridgeUrl) }
  var token by remember { mutableStateOf(prefs.token) }
  var skip by remember { mutableStateOf(prefs.skipPerms) }
  var cwd by remember { mutableStateOf(prefs.defaultCwd) }
  var showToken by remember { mutableStateOf(false) }
  var testing by remember { mutableStateOf(false) }
  var testMsg by remember { mutableStateOf<String?>(null) }
  var testOk by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  Scaffold(topBar = {
    TopAppBar(title = { Text("설정") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(url, { url = it; prefs.bridgeUrl = it.trim() }, Modifier.fillMaxWidth(), label = { Text("브리지 URL") }, singleLine = true)
      OutlinedTextField(token, { token = it; prefs.token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("토큰 (붙여넣기)") }, singleLine = true,
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          IconButton(onClick = { showToken = !showToken }) {
            Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showToken) "토큰 숨기기" else "토큰 보기")
          }
        })
      OutlinedTextField(cwd, { cwd = it; prefs.defaultCwd = it.trim() }, Modifier.fillMaxWidth(), label = { Text("기본 cwd") }, singleLine = true)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("권한 확인 건너뛰기 (skipPerms)")
        Switch(skip, { skip = it; prefs.skipPerms = it })
      }
      if (!skip) Text("경고: 권한 필요한 작업에서 멈춤 (-p 모드는 대화형 승인 불가)")
      Button(enabled = !testing, onClick = {
        testing = true; testMsg = null
        scope.launch {
          val r = BridgeClient.of(prefs).testConnection()
          testOk = r is ConnTest.Ok
          testMsg = when (r) {
            is ConnTest.Ok -> "OK"
            is ConnTest.BadToken -> "토큰 틀림"
            is ConnTest.Error -> "연결 실패: ${r.message}"
          }
          testing = false
        }
      }) { Text(if (testing) "확인 중…" else "연결 테스트") }
      testMsg?.let { Text(it, color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
      Button(onClick = onConnect) { Text("연결 마법사") }
    }
  }
}
