package dev.minedmap.claudedroid.core.termux
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runs a Termux binary through RUN_COMMAND and waits for its result. Shared by chat (bridge start) and control (adb). */
class TermuxRunner(ctx: Context) {
  private val ctx = ctx.applicationContext

  suspend fun runScript(script: String): Result<String> = run("$PREFIX/bin/bash", arrayOf(script))

  suspend fun run(path: String, args: Array<String>): Result<String> {
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
    /** SharedPreferences file shared by every module, so module splits need no data migration. */
    const val PREFS_FILE = "claude_droid"
    internal const val EXTRA_REQ = "reqId"
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
    val id = intent.getIntExtra(TermuxRunner.EXTRA_REQ, -1)
    TermuxRunner.pending[id]?.complete(intent.getBundleExtra("result"))
  }
}
