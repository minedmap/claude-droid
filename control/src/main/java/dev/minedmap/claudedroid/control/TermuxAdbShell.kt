package dev.minedmap.claudedroid.control
import android.content.Context
import dev.minedmap.claudedroid.core.termux.TermuxRunner

data class AdbDevice(val serial: String, val state: String)

/** adb running inside Termux, driven through RUN_COMMAND. Needs no chat bridge and no token. */
class TermuxAdbShell(private val ctx: Context) : PrivilegedShell {
  override val name = "Termux"
  private val prefs = ControlPrefs(ctx)
  private val termux = TermuxRunner(ctx)

  override suspend fun available() = TermuxRunner.isInstalled(ctx) && TermuxRunner.hasPermission(ctx)

  override suspend fun pair(port: Int, code: String) = termux.run(ADB, arrayOf("pair", "localhost:$port", code))

  override suspend fun connect(port: Int): Result<String> {
    prefs.lastConnectPort = port
    return termux.run(ADB, arrayOf("connect", "localhost:$port"))
  }

  override suspend fun exec(cmd: String): Result<String> {
    val port = prefs.lastConnectPort
    val args = if (port > 0) arrayOf("-s", "localhost:$port", "shell", cmd) else arrayOf("shell", cmd)
    return termux.run(ADB, args)
  }

  /** null = Termux/adb unreachable. */
  suspend fun devices(): List<AdbDevice>? =
    if (!available()) null else termux.run(ADB, arrayOf("devices")).getOrNull()?.let { AdbDevices.parse(it) }

  private companion object {
    const val ADB = "${TermuxRunner.PREFIX}/bin/adb"
  }
}

object AdbDevices {
  /** Parses `adb devices` output: `serial<TAB>state` lines; the header and `* daemon ...` notices are skipped. */
  fun parse(out: String): List<AdbDevice> = out.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("List of devices") }
    .map { it.split(Regex("\\s+")) }
    .filter { it.size >= 2 }
    .map { AdbDevice(it[0], it[1]) }.toList()
}
