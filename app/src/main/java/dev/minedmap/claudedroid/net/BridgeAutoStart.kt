package dev.minedmap.claudedroid.net
import android.content.Context
import dev.minedmap.claudedroid.adb.TermuxShell
import dev.minedmap.claudedroid.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object BridgeAutoStart {
  private const val SCRIPT = "/data/data/com.termux/files/home/claude-droid/start.sh"
  private val mutex = Mutex()
  @Volatile private var lastAttempt = 0L

  /** health ok면 true. 아니면 Termux start.sh 실행 후 최대 10초 폴링. 15초 디바운스. */
  suspend fun ensure(ctx: Context): Boolean {
    val app = ctx.applicationContext
    val prefs = Prefs(app)
    val client = BridgeClient.of(prefs)
    return try {
      if (client.health()) return true
      mutex.withLock {
        if (client.health()) return true
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 15_000) return false
        lastAttempt = now
        if (!TermuxShell.isInstalled(app) || !TermuxShell.hasPermission(app)) return false
        TermuxShell(app).runScript(SCRIPT)
        repeat(10) {
          if (client.health()) return true
          delay(1000)
        }
        client.health()
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Throwable) { false }
  }
}
