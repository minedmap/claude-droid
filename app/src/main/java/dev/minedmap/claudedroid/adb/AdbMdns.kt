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
  val lastError = MutableStateFlow<String?>(null)

  private var pairing: Watcher? = null
  private var connect: Watcher? = null

  @Synchronized fun start(ctx: Context) {
    try {
      val app = ctx.applicationContext
      if (pairing == null) pairing = Watcher(app, TLS_PAIRING) { _pairingPort.value = if (it > 0) it else null }
      if (connect == null) connect = Watcher(app, TLS_CONNECT) { _connectPort.value = if (it > 0) it else null }
      pairing?.start(); connect?.start()
    } catch (e: Throwable) {
      lastError.value = "mDNS 시작 실패: ${e.message}"
    }
  }

  @Synchronized fun stop() {
    try { pairing?.stop(); connect?.stop() } catch (e: Throwable) { lastError.value = "mDNS 중지 실패: ${e.message}" }
  }

  private class Watcher(ctx: Context, private val type: String, private val onPort: (Int) -> Unit) {
    private val nsd: NsdManager? = ctx.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private var running = false
    private var active = false        // discovery currently registered or starting
    private var resolving = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var serviceName: String? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private fun newListener() = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(t: String) {}
      override fun onStartDiscoveryFailed(t: String, code: Int) {
        Log.v(TAG, "start failed $t $code")
        synchronized(lock) { if (listener === this) { active = false; listener = null } }
      }
      override fun onDiscoveryStopped(t: String) {
        synchronized(lock) { if (listener === this) { active = false; listener = null } }
      }
      override fun onStopDiscoveryFailed(t: String, code: Int) {
        Log.v(TAG, "stop failed $t $code")
        synchronized(lock) { if (listener === this) { active = false; listener = null } }
      }
      override fun onServiceFound(info: NsdServiceInfo) {
        try {
          synchronized(lock) { queue.addLast(info) }
          pump()
        } catch (e: Throwable) { Log.w(TAG, "found", e) }
      }
      override fun onServiceLost(info: NsdServiceInfo) {
        try { if (info.serviceName == serviceName) onPort(-1) } catch (_: Throwable) {}
      }
    }

    private fun pump() {
      val info: NsdServiceInfo
      synchronized(lock) {
        if (resolving || !running) return
        info = queue.removeFirstOrNull() ?: return
        resolving = true
      }
      try {
        @Suppress("DEPRECATION")
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
          override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done() }
          override fun onServiceResolved(r: NsdServiceInfo) {
            try { resolved(r) } catch (e: Throwable) { Log.w(TAG, "resolved", e) }
            done()
          }
        })
      } catch (e: Throwable) {
        Log.w(TAG, "resolveService", e)
        done()
      }
    }

    private fun done() {
      synchronized(lock) { resolving = false }
      try { pump() } catch (_: Throwable) {}
    }

    fun start() {
      synchronized(lock) {
        running = true
        if (active) return
        active = true
        val l = newListener()
        listener = l
        try {
          nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Throwable) {
          active = false; listener = null
          Log.w(TAG, "discoverServices", e)
        }
      }
    }

    fun stop() {
      synchronized(lock) {
        running = false
        queue.clear()
        val l = listener ?: return
        try { nsd?.stopServiceDiscovery(l) } catch (e: Throwable) { active = false; listener = null }
      }
    }

    private fun resolved(r: NsdServiceInfo) {
      @Suppress("DEPRECATION")
      val host = r.host?.hostAddress ?: return
      val mine = NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.any { ni -> ni.inetAddresses.asSequence().any { it.hostAddress == host } } ?: false
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
