package dev.minedmap.claudedroid.control
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Watches the wireless-debugging connect port (it changes on every toggle) and reconnects adb once paired. */
object ControlAutoConnect {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var started = false

  fun start(ctx: Context) {
    if (Build.VERSION.SDK_INT < 30 || started) return
    started = true
    val app = ctx.applicationContext
    try { AdbMdns.start(app) } catch (_: Throwable) {}
    scope.launch {
      var last = 0
      AdbMdns.connectPort.collect { port ->
        try {
          val prefs = ControlPrefs(app)
          if (port != null && prefs.paired && port != last) {
            val r = Shells.pick(app)?.connect(port)
            if (r != null && r.isSuccess) { last = port; prefs.lastConnectPort = port }
          }
        } catch (_: Throwable) {}
      }
    }
  }
}
