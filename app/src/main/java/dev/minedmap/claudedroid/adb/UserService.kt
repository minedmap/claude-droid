package dev.minedmap.claudedroid.adb
import dev.minedmap.claudedroid.IUserService

/** Runs inside the Shizuku user-service process (uid 2000 when Shizuku started via adb). */
class UserService : IUserService.Stub() {
  override fun destroy() { System.exit(0) }
  override fun exec(cmd: String): String {
    val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out
  }
}
