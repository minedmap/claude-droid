package dev.minedmap.claudedroid.ui
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.minedmap.claudedroid.core.termux.TermuxRunner
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.net.BridgeAutoStart
import dev.minedmap.claudedroid.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Claude Code chat setup only (Termux, bridge, token). Phone control has its own screen in the :control module. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(onBack: () -> Unit, onControl: () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { Prefs(ctx) }
  val scope = rememberCoroutineScope()
  var termux by remember { mutableStateOf(false) }
  var termuxPerm by remember { mutableStateOf(false) }
  var bridge by remember { mutableStateOf(false) }
  var token by remember { mutableStateOf(prefs.token) }
  var info by remember { mutableStateOf("") }
  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { termuxPerm = it }

  LaunchedEffect(Unit) {
    while (true) {
      try {
        termux = TermuxRunner.isInstalled(ctx)
        termuxPerm = TermuxRunner.hasPermission(ctx)
        bridge = BridgeClient.of(prefs).health()
        if (!bridge) {
          scope.launch { runCatching { if (BridgeAutoStart.ensure(ctx)) bridge = true } }
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Throwable) {
        info = "상태 확인 오류: ${e.message}"
      }
      delay(4000)
    }
  }

  fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

  @Composable
  fun Step(title: String, ok: Boolean, content: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (ok) "✅ " else "❌ ") + title)
        content()
      }
    }
  }

  Scaffold(topBar = {
    TopAppBar(title = { Text("연결 마법사 (Claude Code)") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Step("1. Termux 설치", termux) {
        if (!termux) Button(onClick = { open("https://f-droid.org/packages/com.termux/") }) { Text("F-Droid에서 받기") }
      }
      Step("2. RUN_COMMAND 권한 (브리지 자동 시작)", termuxPerm) {
        if (!termuxPerm) Button(onClick = { runCatching { permLauncher.launch(TermuxRunner.PERM) } }) { Text("RUN_COMMAND 권한") }
      }
      Step("3. 토큰", token.isNotEmpty()) {
        OutlinedTextField(token, { token = it; prefs.token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("토큰 (붙여넣기)") },
          singleLine = true, visualTransformation = PasswordVisualTransformation())
      }
      Step("4. 브리지 /health", bridge) {
        if (!bridge) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { scope.launch { info = try { BridgeAutoStart.startService(ctx).let { it.getOrNull() ?: ("실패: " + it.exceptionOrNull()?.message) } } catch (e: Exception) { "오류: ${e.message}" } } }) { Text("브리지 시작") }
          Button(onClick = { ctx.packageManager.getLaunchIntentForPackage("com.termux")?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Termux 열기") }
        }
      }
      if (info.isNotEmpty()) Text(info)
      Text("Termux 백그라운드 사망 방지: 배터리 최적화에서 Termux를 제외하세요.")
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("폰 제어(무선 디버깅 페어링)는 별도 기능입니다. Claude Code 없이도 사용할 수 있습니다.")
          OutlinedButton(onClick = onControl) { Text("폰 제어 (adb) 설정") }
        }
      }
    }
  }
}
