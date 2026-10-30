package dev.minedmap.claudedroid.adb
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import dev.minedmap.claudedroid.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxShell(private val ctx: Context) : PrivilegedShell {
  override val name = "Termux"
  private val prefs = Prefs(ctx)

  override suspend fun available() = isInstalled(ctx) && hasPermission(ctx)

  override suspend fun pair(port: Int, code: String) = run("$PREFIX/bin/adb", arrayOf("pair", "localhost:$port", code))

  override suspend fun connect(port: Int): Result<String> {
    prefs.lastConnectPort = port
    return run("$PREFIX/bin/adb", arrayOf("connect", "localhost:$port"))
  }

  override suspend fun exec(cmd: String): Result<String> {
    val port = prefs.lastConnectPort
    val args = if (port > 0) arrayOf("-s", "localhost:$port", "shell", cmd) else arrayOf("shell", cmd)
    return run("$PREFIX/bin/adb", args)
  }

  suspend fun startBridge() = run("$PREFIX/bin/sv", arrayOf("up", "$PREFIX/var/service/claude-droid"))

  suspend fun runScript(script: String): Result<String> = run("$PREFIX/bin/bash", arrayOf(script))

  private suspend fun run(path: String, args: Array<String>): Result<String> {
    val id = counter.incrementAndGet()
    val d = CompletableDeferred<Bundle?>()
    pending[id] = d
    return try {
      val pi = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, TermuxResultReceiver::class.java).putExtra(EXTRA_REQ, id),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
        .putExtra("com.termux.RUN_COMMAND_PATH", path)
        .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
      ctx.startService(i)
      val b = withTimeout(20_000) { d.await() }
      if (b == null) Result.failure(IllegalStateException("no result"))
      else {
        val out = (b.getString("stdout").orEmpty() + b.getString("stderr").orEmpty()).trim()
        val err = b.getString("errmsg").orEmpty()
        if (b.getInt("exitCode", -1) == 0) Result.success(out)
        else Result.failure(IllegalStateException(listOf(out, err).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { "exit ${b.getInt("exitCode", -1)}" }))
      }
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      pending.remove(id)
    }
  }

  companion object {
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val PERM = "com.termux.permission.RUN_COMMAND"
    const val EXTRA_REQ = "reqId"
    private val counter = AtomicInteger(1000)
    internal val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle?>>()

    fun isInstalled(ctx: Context) = try {
      ctx.packageManager.getPackageInfo("com.termux", 0); true
    } catch (_: Exception) { false }

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED
  }
}

class TermuxResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getIntExtra(TermuxShell.EXTRA_REQ, -1)
    TermuxShell.pending[id]?.complete(intent.getBundleExtra("result"))
  }
}
