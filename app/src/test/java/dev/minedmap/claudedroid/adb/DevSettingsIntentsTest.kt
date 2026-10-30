package dev.minedmap.claudedroid.adb
import org.junit.Assert.*
import org.junit.Test
class DevSettingsIntentsTest {
  @Test fun wirelessSpec() {
    val s = DevSettingsIntents.wirelessDebuggingSpec()
    assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", s.action)
    assertEquals("toggle_adb_wireless", s.extras[":settings:fragment_args_key"])
  }
  @Test fun fallbackOrder() =
    assertEquals(listOf("wireless", "developer", "about_phone"), DevSettingsIntents.fallbackOrder)
}
