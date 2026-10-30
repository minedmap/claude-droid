// Adapted from RikkaApps/Shizuku (Apache-2.0): RemoteInput reply handling in AdbPairingService.kt
package dev.minedmap.claudedroid.adb
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.minedmap.claudedroid.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PairingReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotification.KEY_CODE)?.toString()?.trim()
    val app = context.applicationContext
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val port = AdbMdns.pairingPort.value
        if (code.isNullOrEmpty() || port == null) {
          PairingNotification.show(app, "실패: 코드 또는 페어링 포트 없음 (페어링 창을 열어두세요)")
          return@launch
        }
        val shell = Shells.pick(app)
        if (shell == null) { PairingNotification.show(app, "실패: 권한 셸 없음", false); return@launch }
        val p = shell.pair(port, code)
        if (p.isFailure) { PairingNotification.show(app, "실패: ${p.exceptionOrNull()?.message}"); return@launch }
        val cp = withTimeoutOrNull(10_000) { AdbMdns.connectPort.filterNotNull().first() }
        if (cp == null) { PairingNotification.show(app, "실패: 연결 포트 못 찾음"); return@launch }
        val c = shell.connect(cp)
        if (c.isSuccess) { Prefs(app).lastConnectPort = cp; PairingNotification.show(app, "연결됨", false) }
        else PairingNotification.show(app, "실패: ${c.exceptionOrNull()?.message}")
      } finally {
        pending.finish()
      }
    }
  }
}
