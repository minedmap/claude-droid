package dev.minedmap.claudedroid.control
import org.junit.Assert.*
import org.junit.Test
class AdbDevicesTest {
  @Test fun parsesSerialAndState() {
    val out = "List of devices attached\nlocalhost:40123\tdevice\nemulator-5554\toffline\n\n"
    assertEquals(listOf(AdbDevice("localhost:40123", "device"), AdbDevice("emulator-5554", "offline")), AdbDevices.parse(out))
  }
  @Test fun skipsDaemonNoticesAndEmptyList() {
    assertEquals(emptyList<AdbDevice>(), AdbDevices.parse("List of devices attached\n* daemon not running; starting now at tcp:5037\n* daemon started successfully"))
    assertEquals(emptyList<AdbDevice>(), AdbDevices.parse(""))
  }
}
