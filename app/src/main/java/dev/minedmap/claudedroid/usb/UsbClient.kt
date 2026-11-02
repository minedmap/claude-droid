package dev.minedmap.claudedroid.usb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UsbDeviceRow(val id: String, val vidpid: String, val title: String, val hasPermission: Boolean, val driver: String?)
data class UsbStatus(val up: Boolean, val devices: List<UsbDeviceRow>)

/** In-app probe of the local USB API (same HTTP surface Claude uses). */
object UsbClient {
  private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
  private val base = "http://127.0.0.1:${UsbHttpServer.DEFAULT_PORT}"

  private fun get(path: String, token: String): JSONObject? = try {
    http.newCall(Request.Builder().url(base + path).header("X-Token", token).build()).execute().use {
      if (!it.isSuccessful) null else JSONObject(it.body!!.string())
    }
  } catch (_: Exception) { null }

  suspend fun status(token: String): UsbStatus = withContext(Dispatchers.IO) {
    val h = get("/usb/health", token) ?: return@withContext UsbStatus(false, emptyList())
    val d = get("/usb/devices", token)
    val rows = ArrayList<UsbDeviceRow>()
    val arr = d?.optJSONArray("devices")
    if (arr != null) for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      val name = o.optString("product", "").ifEmpty { o.optString("knownName", "") }.ifEmpty { "USB 장치" }
      rows.add(UsbDeviceRow(o.getString("id"), o.optString("vidpid"), name, o.optBoolean("hasPermission"),
        o.optString("serialDriver", "").ifEmpty { null }))
    }
    UsbStatus(h.optBoolean("ok", false), rows)
  }

  suspend fun requestPermission(token: String, id: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val body = JSONObject().put("device", id).toString().toRequestBody("application/json".toMediaType())
      http.newCall(Request.Builder().url("$base/usb/permission").header("X-Token", token).post(body).build()).execute().use {
        it.isSuccessful && JSONObject(it.body!!.string()).optBoolean("granted", false)
      }
    } catch (_: Exception) { false }
  }
}
