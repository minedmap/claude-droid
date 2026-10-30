package dev.minedmap.claudedroid
import android.app.Application
import android.os.Build
import dev.minedmap.claudedroid.adb.AdbMdns
import dev.minedmap.claudedroid.adb.Shells
import dev.minedmap.claudedroid.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        File(filesDir, "crash.txt").writeText("thread=${t.name} sdk=${Build.VERSION.SDK_INT} ${Build.MODEL}\n$sw")
      } catch (_: Throwable) {}
      prev?.uncaughtException(t, e)
    }
    if (Build.VERSION.SDK_INT >= 30) {
      try { AdbMdns.start(this) } catch (_: Throwable) {}
      scope.launch {
        var last = 0
        AdbMdns.connectPort.collect { port ->
          try {
            val prefs = Prefs(this@App)
            if (port != null && prefs.paired && port != last) {
              val r = Shells.pick(this@App)?.connect(port)
              if (r != null && r.isSuccess) { last = port; prefs.lastConnectPort = port }
            }
          } catch (_: Throwable) {}
        }
      }
    }
  }
}
