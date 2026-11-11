package dev.minedmap.claudedroid
import android.app.Application
import android.os.Build
import dev.minedmap.claudedroid.control.ControlAutoConnect
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
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
    ControlAutoConnect.start(this)
  }
}
