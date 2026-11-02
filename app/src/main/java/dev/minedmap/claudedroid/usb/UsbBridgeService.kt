package dev.minedmap.claudedroid.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.minedmap.claudedroid.data.Prefs

/** Foreground service (dataSync) that hosts the loopback USB API on 127.0.0.1:8766. */
class UsbBridgeService : Service() {
  private var server: UsbHttpServer? = null
  private var serialHub: SerialHub? = null
  private var usbHub: UsbHub? = null

  companion object {
    private const val CHANNEL = "usb_bridge"
    private const val NOTIF_ID = 4102

    fun start(ctx: Context) {
      try { ContextCompat.startForegroundService(ctx, Intent(ctx, UsbBridgeService::class.java)) } catch (_: Throwable) {}
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // must call startForeground promptly after startForegroundService()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB 브리지", NotificationManager.IMPORTANCE_LOW))
    val n = Notification.Builder(this, CHANNEL)
      .setContentTitle("Claude Droid USB")
      .setContentText("USB 제어 서버 127.0.0.1:${UsbHttpServer.DEFAULT_PORT}")
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(NOTIF_ID, n)
    if (server == null) {
      try {
        val hub = UsbHub(this)
        hub.register()
        val serial = SerialHub(hub)
        hub.onDetached = { serial.closeDevice(it) }
        usbHub = hub; serialHub = serial
        val prefs = Prefs(this)
        val s = UsbHttpServer(hub, serial, { prefs.token })
        s.start()
        server = s
      } catch (e: Throwable) {
        stopSelf()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    server?.stop(); server = null
    serialHub?.closeAll(); serialHub = null
    usbHub?.unregister(); usbHub = null
    super.onDestroy()
  }
}
