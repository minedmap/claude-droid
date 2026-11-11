package dev.minedmap.claudedroid.control
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.minedmap.claudedroid.core.termux.TermuxRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

const val CONTROL_SETUP_COMMAND = "bash ~/control/setup.sh"

/** Phone control (adb) setup. Independent of the Claude Code chat: needs no bridge and no token. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ControlPrefs(ctx) }
  val scope = rememberCoroutineScope()
  var termux by remember { mutableStateOf(false) }
  var termuxPerm by remember { mutableStateOf(false) }
  var dev by remember { mutableStateOf(false) }
  var wifiDbg by remember { mutableStateOf(false) }
  var adbUp by remember { mutableStateOf<Boolean?>(null) }
  var shellName by remember { mutableStateOf("없음") }
  var testOut by remember { mutableStateOf("") }
  var info by remember { mutableStateOf("") }
  var refresh by remember { mutableIntStateOf(0) }

  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) runCatching { PairingNotification.show(ctx) }
  }
  val mdnsPair by AdbMdns.pairingPort.collectAsState()
  val mdnsConn by AdbMdns.connectPort.collectAsState()
  var pairPortTxt by remember { mutableStateOf("") }
  var pairCodeTxt by remember { mutableStateOf("") }
  var connPortTxt by remember { mutableStateOf("") }
  var pairResult by remember { mutableStateOf("") }
  var pairing by remember { mutableStateOf(false) }
  LaunchedEffect(mdnsPair) { if (mdnsPair != null) pairPortTxt = mdnsPair.toString() }

  // Cheap local checks every 3 s; `adb devices` goes through Termux RUN_COMMAND, so only every 4th tick (~12 s) or on demand.
  LaunchedEffect(refresh) {
    var tick = 0
    while (true) {
      try {
        termux = TermuxRunner.isInstalled(ctx)
        termuxPerm = TermuxRunner.hasPermission(ctx)
        dev = DevSettingsIntents.devOptionsEnabled(ctx)
        wifiDbg = runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        shellName = Shells.pick(ctx)?.name ?: "없음"
        if (tick % 4 == 0) adbUp = TermuxAdbShell(ctx).devices()?.any { it.state == "device" }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        info = "상태 확인 오류: ${e.message}"
      }
      tick++
      delay(3000)
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
    TopAppBar(title = { Text("폰 제어 (adb)") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Claude Code 브리지나 토큰 없이도 동작합니다. 페어링 후 Termux의 MCP 클라이언트(Claude Code, Codex, Gemini CLI 등)가 폰을 조작할 수 있습니다.")
      Step("1. Termux + RUN_COMMAND 권한", termux && termuxPerm) {
        if (!termux) Button(onClick = { open("https://f-droid.org/packages/com.termux/") }) { Text("F-Droid에서 받기") }
        else if (!termuxPerm) {
          Button(onClick = { runCatching { permLauncher.launch(TermuxRunner.PERM) } }) { Text("RUN_COMMAND 권한") }
          Text("Termux에서 $CONTROL_SETUP_COMMAND 를 먼저 실행해야 권한 요청이 동작합니다 (allow-external-apps).")
        }
      }
      Step("2. 개발자 옵션", dev) {
        if (!dev) {
          Button(onClick = { runCatching { DevSettingsIntents.open(ctx) }.onFailure { info = "오류: ${it.message}" } }) { Text("빌드번호 화면 열기") }
          Text("빌드번호를 7번 탭하세요.")
        }
      }
      if (Build.VERSION.SDK_INT >= 30) {
        Step("3. 무선 디버깅", adbUp == true) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("무선 디버깅: " + (if (wifiDbg) "켜짐" else "꺼짐") + " · adb: " + when (adbUp) {
              true -> "연결됨"; false -> "끊김"; null -> if (termux && termuxPerm) "확인 중…" else "확인 불가(Termux 권한 필요)"
            }, Modifier.weight(1f))
            OutlinedButton(onClick = { adbUp = null; refresh++ }) { Text("새로고침") }
          }
          Button(onClick = {
            try {
              AdbMdns.start(ctx)
              if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              else PairingNotification.show(ctx)
              DevSettingsIntents.open(ctx)
            } catch (e: Throwable) { info = "오류: ${e.message}" }
          }) { Text("무선 디버깅 열기") }
          Text("'페어링 코드로 기기 페어링'을 누르고 알림에 코드를 입력하거나 아래에 직접 입력하세요.")
          Text("페어링 포트: ${mdnsPair ?: "검색 중…"}")
          Text("연결 포트: ${mdnsConn ?: "검색 중…"}")
          AdbMdns.lastError.collectAsState().value?.let { Text(it) }
          OutlinedTextField(pairPortTxt, { pairPortTxt = it.filter(Char::isDigit) }, label = { Text("페어링 포트") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(pairCodeTxt, { pairCodeTxt = it.filter(Char::isDigit) }, label = { Text("페어링 코드") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(connPortTxt, { connPortTxt = it.filter(Char::isDigit) }, label = { Text("연결 포트 (선택)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          Button(enabled = !pairing, onClick = {
            val pp = pairPortTxt.toIntOrNull()
            if (pp == null || pairCodeTxt.isBlank()) { pairResult = "페어링 포트와 코드를 입력하세요"; return@Button }
            pairing = true; pairResult = "페어링 중…"
            scope.launch {
              try {
                val sh = Shells.pick(ctx)
                if (sh == null) { pairResult = "실패: 권한 셸 없음"; return@launch }
                val p = sh.pair(pp, pairCodeTxt.trim())
                if (p.isFailure) { pairResult = "페어링 실패: ${p.exceptionOrNull()?.message}"; return@launch }
                val cp = connPortTxt.toIntOrNull() ?: withTimeoutOrNull(10_000) { AdbMdns.connectPort.filterNotNull().first() }
                if (cp == null) { pairResult = "페어링 성공, 연결 포트 못 찾음 (연결 포트 입력)"; prefs.paired = true; return@launch }
                val c = sh.connect(cp)
                if (c.isSuccess) {
                  prefs.lastConnectPort = cp; prefs.paired = true
                  pairResult = "연결됨 (포트 $cp)"
                  refresh++
                } else pairResult = "연결 실패: ${c.exceptionOrNull()?.message}"
              } catch (e: CancellationException) {
                throw e
              } catch (e: Throwable) {
                pairResult = "오류: ${e.message}"
              } finally { pairing = false }
            }
          }) { Text("페어링") }
          if (pairResult.isNotEmpty()) Text(pairResult)
        }
      }
      Step("4. 권한 셸: $shellName", shellName != "없음") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { try { Shizuku.requestPermission(1) } catch (e: Throwable) { info = "Shizuku 실행 안 됨" } }) { Text("Shizuku 권한") }
          Button(onClick = { scope.launch {
            val sh = Shells.pick(ctx)
            testOut = try {
              if (sh == null) "셸 없음" else sh.exec("id").let { it.getOrNull() ?: ("실패: " + it.exceptionOrNull()?.message) }
            } catch (e: Exception) { "오류: ${e.message}" }
          } }) { Text("연결 테스트") }
        }
        if (testOut.isNotEmpty()) Text(testOut)
      }
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("MCP 설정 (Termux에서 실행)")
          Text(CONTROL_SETUP_COMMAND, fontFamily = FontFamily.Monospace)
          Text("phone MCP 서버(스크린샷/탭/스와이프/입력/UI 트리)를 설치하고 Claude Code에 등록합니다. 다른 MCP 클라이언트용 설정도 출력합니다.")
          OutlinedButton(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("command", CONTROL_SETUP_COMMAND))
          }) { Text("명령 복사") }
        }
      }
      if (info.isNotEmpty()) Text(info)
    }
  }
}
