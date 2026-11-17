package dev.minedmap.claudedroid.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yXmlTest {
  @Test fun escapesAndNests() {
    val leaf = A11yNode("android.widget.Button", "p", "A & \"B\" <c>", "", "p:id/ok", 1, 2, 3, 4, clickable = true, enabled = false)
    val x = A11yXml.write(listOf(A11yNode("android.widget.FrameLayout", "p", "", "", "", 0, 0, 10, 10, children = listOf(leaf))))
    assertTrue(x.contains("<hierarchy rotation=\"0\">"))
    assertTrue(x.contains("text=\"A &amp; &quot;B&quot; &lt;c&gt;\""))
    assertTrue(x.contains("bounds=\"[1,2][3,4]\""))
    assertTrue(x.contains("enabled=\"false\""))
    assertEquals(1, "</node>".toRegex().findAll(x).count())
    assertTrue(x.endsWith("</hierarchy>"))
  }
  @Test fun dropsControlChars() { assertEquals("ab", A11yXml.esc("a\u0001b")) }
}
