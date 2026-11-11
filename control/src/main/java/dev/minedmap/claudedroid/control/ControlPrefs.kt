package dev.minedmap.claudedroid.control
import android.content.Context
import dev.minedmap.claudedroid.core.termux.TermuxRunner

/** Pairing state. Same file and keys as before the module split, so upgrades keep their pairing. */
class ControlPrefs(ctx: Context) {
  private val sp = ctx.applicationContext.getSharedPreferences(TermuxRunner.PREFS_FILE, Context.MODE_PRIVATE)
  var lastConnectPort: Int
    get() = sp.getInt("lastConnectPort", 0)
    set(v) = sp.edit().putInt("lastConnectPort", v).apply()
  var paired: Boolean
    get() = sp.getBoolean("paired", false)
    set(v) = sp.edit().putBoolean("paired", v).apply()
}
