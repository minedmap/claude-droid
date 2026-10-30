package dev.minedmap.claudedroid
import android.Manifest
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dev.minedmap.claudedroid.net.BridgeAutoStart
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.minedmap.claudedroid.data.Prefs
import dev.minedmap.claudedroid.ui.ChatScreen
import dev.minedmap.claudedroid.ui.ConnectScreen
import dev.minedmap.claudedroid.ui.SessionListScreen
import dev.minedmap.claudedroid.ui.SettingsScreen

class MainActivity : ComponentActivity() {
  override fun onResume() {
    super.onResume()
    lifecycleScope.launch { try { BridgeAutoStart.ensure(this@MainActivity) } catch (_: Throwable) {} }
  }

  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    enableEdgeToEdge()
    val prefs = Prefs(this)
    setContent {
      MaterialTheme {
        val crashFile = remember { File(filesDir, "crash.txt") }
        var crashText by remember { mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null) }
        crashText?.let { txt ->
          AlertDialog(
            onDismissRequest = {},
            title = { Text("이전 실행 크래시") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
              Text(txt, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } },
            confirmButton = { TextButton(onClick = {
              val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("crash", txt))
            }) { Text("복사") } },
            dismissButton = { TextButton(onClick = {
              runCatching { crashFile.delete() }; crashText = null
            }) { Text("닫기") } })
        }
        val nav = rememberNavController()
        val start = remember { if (prefs.token.isEmpty()) "connect" else "list" }
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
          if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        NavHost(nav, startDestination = start) {
          composable("list") {
            SessionListScreen(
              onOpen = { nav.navigate("chat/$it") },
              onSettings = { nav.navigate("settings") },
              onConnect = { nav.navigate("connect") })
          }
          composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            ChatScreen(e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onConnect = { nav.navigate("connect") })
          }
          composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onConnect = { nav.navigate("connect") }) }
          composable("connect") { ConnectScreen(onBack = {
            if (!nav.popBackStack()) nav.navigate("list") { popUpTo("connect") { inclusive = true } }
          }) }
        }
      }
    }
  }
}
