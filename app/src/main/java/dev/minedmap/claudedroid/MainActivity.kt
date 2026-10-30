package dev.minedmap.claudedroid
import android.Manifest
import android.os.Build
import android.os.Bundle
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
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    enableEdgeToEdge()
    val prefs = Prefs(this)
    setContent {
      MaterialTheme {
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
