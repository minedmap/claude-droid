package dev.minedmap.claudedroid
import dev.minedmap.claudedroid.ui.SizedLru
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class SizedLruTest {
  private fun lru(max: Long) = SizedLru<String, ByteArray>(max) { it.size.toLong() }
  @Test fun evictsLeastRecentlyUsed() {
    val c = lru(10)
    c.put("a", ByteArray(4)); c.put("b", ByteArray(4))
    c.get("a")                       // a is now most recent
    c.put("c", ByteArray(4))         // 12 > 10 -> evict b
    assertNull(c.get("b"))
    assertEquals(4, c.get("a")!!.size)
    assertEquals(8L, c.weight())
  }
  @Test fun replaceUpdatesWeight() {
    val c = lru(10)
    c.put("a", ByteArray(6)); c.put("a", ByteArray(2))
    assertEquals(2L, c.weight()); assertEquals(1, c.size())
  }
  @Test fun oversizedNotStored() {
    val c = lru(10)
    c.put("a", ByteArray(4)); c.put("a", ByteArray(11))
    assertNull(c.get("a")); assertEquals(0L, c.weight())
  }
}
