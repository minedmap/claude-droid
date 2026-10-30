package dev.minedmap.claudedroid.adb
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.minedmap.claudedroid.IUserService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

class ShizukuShell(private val ctx: Context) : PrivilegedShell {
  override val name = "Shizuku"

  override suspend fun available(): Boolean = try {
    Shizuku.pingBinder() && !Shizuku.isPreV11() &&
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
  } catch (_: Throwable) { false }

  override suspend fun pair(port: Int, code: String) = Result.success("shizuku: not needed")
  override suspend fun connect(port: Int) = Result.success("shizuku: not needed")

  override suspend fun exec(cmd: String): Result<String> = runCatching {
    val s = service()
    s.exec(cmd)
  }

  private suspend fun service(): IUserService {
    svc?.let { if (it.asBinder().pingBinder()) return it }
    return withTimeout(10_000) {
      suspendCancellableCoroutine<IUserService> { cont ->
        val conn = object : ServiceConnection {
          override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = IUserService.Stub.asInterface(b)
            svc = s
            if (cont.isActive) cont.resume(s)
          }
          override fun onServiceDisconnected(n: ComponentName) { svc = null }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, UserService::class.java.name))
          .processNameSuffix("shell").version(1)
        Shizuku.bindUserService(args, conn)
      }
    }
  }

  companion object {
    @Volatile private var svc: IUserService? = null
  }
}
