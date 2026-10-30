package dev.minedmap.claudedroid.data
import android.content.Context

class Prefs(ctx: Context) {
  private val sp = ctx.applicationContext.getSharedPreferences("claude_droid", Context.MODE_PRIVATE)
  var bridgeUrl: String
    get() = sp.getString("bridgeUrl", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
    set(v) = sp.edit().putString("bridgeUrl", v).apply()
  var token: String
    get() = sp.getString("token", "") ?: ""
    set(v) = sp.edit().putString("token", v).apply()
  var skipPerms: Boolean
    get() = sp.getBoolean("skipPerms", true)
    set(v) = sp.edit().putBoolean("skipPerms", v).apply()
  var defaultCwd: String
    get() = sp.getString("defaultCwd", "/data/data/com.termux/files/home") ?: "/data/data/com.termux/files/home"
    set(v) = sp.edit().putString("defaultCwd", v).apply()
  var lastConnectPort: Int
    get() = sp.getInt("lastConnectPort", 0)
    set(v) = sp.edit().putInt("lastConnectPort", v).apply()
}
