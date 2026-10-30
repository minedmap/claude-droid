// Adapted from RikkaApps/Shizuku (Apache-2.0): manager/.../adb/AdbPairingService.kt
package dev.minedmap.claudedroid.adb
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent

object PairingNotification {
  const val CHANNEL = "pairing"
  const val KEY_CODE = "code"
  private const val ID = 4242

  fun show(ctx: Context, text: String = "무선 디버깅 페어링 코드를 입력하세요", withInput: Boolean = true) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "ADB pairing", NotificationManager.IMPORTANCE_HIGH))
    val b = Notification.Builder(ctx, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Claude Droid")
      .setContentText(text)
      .setOnlyAlertOnce(true)
    if (withInput) {
      val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, PairingReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val ri = RemoteInput.Builder(KEY_CODE).setLabel("페어링 코드").build()
      b.addAction(@Suppress("DEPRECATION") Notification.Action.Builder(android.R.drawable.ic_input_add, "페어링 코드 입력", pi).addRemoteInput(ri).build())
      b.setOngoing(true)
    }
    try { nm.notify(ID, b.build()) } catch (_: SecurityException) {}
  }
}
