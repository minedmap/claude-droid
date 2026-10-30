// Adapted from RikkaApps/Shizuku (Apache-2.0): manager/.../adb/AdbMdns.kt
package dev.minedmap.claudedroid.adb
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/** Discovers wireless-debugging pairing / connect ports via mDNS. Needs Android 11+. */
object AdbMdns {
  const val TLS_CONNECT = "_adb-tls-connect._tcp"
  const val TLS_PAIRING = "_adb-tls-pairing._tcp"
  private const val TAG = "AdbMdns"

  private val _pairingPort = MutableStateFlow<Int?>(null)
  private val _connectPort = MutableStateFlow<Int?>(null)
  val pairingPort: StateFlow<Int?> = _pairingPort
  val connectPort: StateFlow<Int?> = _connectPort

  private var pairing: Watcher? = null
  private var connect: Watcher? = null

  @Synchronized fun start(ctx: Context) {
    val app = ctx.applicationContext
    if (pairing == null) pairing = Watcher(app, TLS_PAIRING) { _pairingPort.value = if (it > 0) it else null }
    if (connect == null) connect = Watcher(app, TLS_CONNECT) { _connectPort.value = if (it > 0) it else null }
    pairing?.start(); connect?.start()
  }

  @Synchronized fun stop() { pairing?.stop(); connect?.stop() }

  private class Watcher(ctx: Context, private val type: String, private val onPort: (Int) -> Unit) {
    private val nsd: NsdManager = ctx.getSystemService(NsdManager::class.java)
    private var running = false
    private var registered = false
    private var serviceName: String? = null

    private val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(t: String) { registered = true }
      override fun onStartDiscoveryFailed(t: String, code: Int) { Log.v(TAG, "start failed $t $code") }
      override fun onDiscoveryStopped(t: String) { registered = false }
      override fun onStopDiscoveryFailed(t: String, code: Int) { Log.v(TAG, "stop failed $t $code") }
      override fun onServiceFound(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
          override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
          override fun onServiceResolved(r: NsdServiceInfo) { resolved(r) }
        })
      }
      override fun onServiceLost(info: NsdServiceInfo) { if (info.serviceName == serviceName) onPort(-1) }
    }

    fun start() {
      if (running) return
      running = true
      if (!registered) nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
      if (!running) return
      running = false
      if (registered) nsd.stopServiceDiscovery(listener)
    }

    private fun resolved(r: NsdServiceInfo) {
      @Suppress("DEPRECATION")
      val host = r.host?.hostAddress ?: return
      val mine = NetworkInterface.getNetworkInterfaces().asSequence()
        .any { ni -> ni.inetAddresses.asSequence().any { it.hostAddress == host } }
      if (running && mine && inUse(r.port)) {
        serviceName = r.serviceName
        onPort(r.port)
      }
    }

    // port must already be bound by adbd; a successful bind here means nobody listens
    private fun inUse(port: Int) = try {
      ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port), 1); false }
    } catch (_: IOException) { true }
  }
}
