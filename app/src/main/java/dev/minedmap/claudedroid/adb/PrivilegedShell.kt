package dev.minedmap.claudedroid.adb
import android.content.Context

interface PrivilegedShell {
  val name: String
  suspend fun available(): Boolean
  suspend fun pair(port: Int, code: String): Result<String>
  suspend fun connect(port: Int): Result<String>
  suspend fun exec(cmd: String): Result<String>
}

object Shells {
  suspend fun pick(ctx: Context): PrivilegedShell? =
    listOf(ShizukuShell(ctx), TermuxShell(ctx)).firstOrNull { it.available() }
}
