package dev.minedmap.claudedroid.adb
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

data class IntentSpec(val action: String, val extras: Map<String, String> = emptyMap())

object DevSettingsIntents {
  // Confirmed via grep in RikkaApps/Shizuku (AdbPairingTutorialActivity.kt)
  private const val ARGS_KEY = ":settings:fragment_args_key"
  private const val ACTION_DEV = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
  private const val ACTION_INFO = "android.settings.DEVICE_INFO_SETTINGS"
  val fallbackOrder = listOf("wireless", "developer", "about_phone")

  fun wirelessDebuggingSpec() = IntentSpec(ACTION_DEV, mapOf(ARGS_KEY to "toggle_adb_wireless"))
  private fun spec(kind: String) = when (kind) {
    "wireless" -> wirelessDebuggingSpec()
    "developer" -> IntentSpec(ACTION_DEV)
    else -> IntentSpec(ACTION_INFO)
  }

  fun devOptionsEnabled(ctx: Context) =
    Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

  /** Returns kind of screen actually opened. Dev options off -> about_phone only. */
  fun open(ctx: Context): String {
    val dev = devOptionsEnabled(ctx)
    for (k in fallbackOrder) {
      if (!dev && k != "about_phone") continue
      val s = spec(k)
      val i = Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
      s.extras.forEach { (key, v) ->
        i.putExtra(key, v)
        i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(key, v) })
      }
      try { ctx.startActivity(i); return k } catch (_: ActivityNotFoundException) {}
    }
    return "none"
  }
}
