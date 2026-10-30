package dev.minedmap.claudedroid.ui
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import dev.minedmap.claudedroid.adb.AdbMdns
import dev.minedmap.claudedroid.adb.DevSettingsIntents
import dev.minedmap.claudedroid.adb.PairingNotification
import dev.minedmap.claudedroid.adb.Shells
import dev.minedmap.claudedroid.adb.TermuxShell
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { Prefs(ctx) }
  val scope = rememberCoroutineScope()
  var termux by remember { mutableStateOf(false) }
  var bridge by remember { mutableStateOf(false) }
  var dev by remember { mutableStateOf(false) }
  var shellName by remember { mutableStateOf("없음") }
  var testOut by remember { mutableStateOf("") }
  var info by remember { mutableStateOf("") }

  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

  LaunchedEffect(Unit) {
    while (true) {
      termux = TermuxShell.isInstalled(ctx)
      bridge = BridgeClient(prefs.bridgeUrl, prefs.token).health()
      dev = DevSettingsIntents.devOptionsEnabled(ctx)
      shellName = Shells.pick(ctx)?.name ?: "없음"
      delay(1000)
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
    TopAppBar(title = { Text("연결 마법사") },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Step("1. Termux 설치", termux) {
        if (!termux) Button(onClick = { open("https://f-droid.org/packages/com.termux/") }) { Text("F-Droid에서 받기") }
      }
      Step("2. 브리지 /health", bridge) {
        if (!bridge) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { scope.launch { info = TermuxShell(ctx).startBridge().let { it.getOrNull() ?: ("실패: " + it.exceptionOrNull()?.message) } } }) { Text("브리지 시작") }
          Button(onClick = { ctx.packageManager.getLaunchIntentForPackage("com.termux")?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Termux 열기") }
        }
      }
      Step("3. 개발자 옵션", dev) {
        if (!dev) {
          Button(onClick = { DevSettingsIntents.open(ctx) }) { Text("빌드번호 화면 열기") }
          Text("빌드번호를 7번 탭하세요.")
        }
      }
      if (Build.VERSION.SDK_INT >= 30) {
        Step("4. 무선 디버깅", prefs.lastConnectPort > 0) {
          Button(onClick = {
            DevSettingsIntents.open(ctx)
            PairingNotification.show(ctx)
            AdbMdns.start(ctx)
          }) { Text("무선 디버깅 열기") }
          Text("'페어링 코드로 기기 페어링'을 누르고 알림에 코드를 입력하세요.")
        }
      }
      Step("5. 권한 셸: $shellName", shellName != "없음") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { try { Shizuku.requestPermission(1) } catch (e: Throwable) { info = "Shizuku 실행 안 됨" } }) { Text("Shizuku 권한") }
          Button(onClick = { permLauncher.launch(TermuxShell.PERM) }) { Text("RUN_COMMAND 권한") }
        }
        Button(onClick = { scope.launch {
          val sh = Shells.pick(ctx)
          testOut = if (sh == null) "셸 없음" else sh.exec("id").let { it.getOrNull() ?: ("실패: " + it.exceptionOrNull()?.message) }
        } }) { Text("연결 테스트") }
        if (testOut.isNotEmpty()) Text(testOut)
      }
      if (info.isNotEmpty()) Text(info)
      Text("Termux 백그라운드 사망 방지: 배터리 최적화에서 Termux를 제외하세요.")
    }
  }
}
