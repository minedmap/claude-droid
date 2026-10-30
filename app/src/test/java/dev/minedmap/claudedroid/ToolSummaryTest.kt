package dev.minedmap.claudedroid
import dev.minedmap.claudedroid.net.ToolSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class ToolSummaryTest {
  @Test fun bashCommand() = assertEquals("ls -la /tmp", ToolSummary.summary("Bash", """{"command":"ls -la \/tmp"}"""))
  @Test fun filePathTools() {
    for (n in listOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit"))
      assertEquals("/a/b.kt", ToolSummary.summary(n, """{"file_path":"\/a\/b.kt"}"""))
  }
  @Test fun notebookPathFallback() = assertEquals("/n.ipynb", ToolSummary.summary("NotebookEdit", """{"notebook_path":"/n.ipynb"}"""))
  @Test fun grepGlobPattern() {
    assertEquals("foo.*bar", ToolSummary.summary("Grep", """{"pattern":"foo.*bar"}"""))
    assertEquals("**/*.kt", ToolSummary.summary("Glob", """{"pattern":"**\/*.kt"}"""))
  }
  @Test fun otherToolCompactJsonUnescaped() = assertEquals("""{"url":"http://x/y"}""", ToolSummary.summary("WebFetch", """{"url":"http:\/\/x\/y"}"""))
  @Test fun missingKeyFallsBackToJson() = assertEquals("""{"x":1}""", ToolSummary.summary("Bash", """{"x":1}"""))
  @Test fun badJsonKept() = assertEquals("nope", ToolSummary.summary("Bash", "nope"))
  @Test fun multilineCommandOneLine() = assertEquals("a && b", ToolSummary.summary("Bash", "{\"command\":\"a &&\\nb\"}"))
  @Test fun longSummaryCut() {
    val s = ToolSummary.summary("Bash", """{"command":"${"x".repeat(500)}"}""")
    assertEquals(201, s.length)
    assertTrue(s.endsWith("…"))
  }
  @Test fun formatHasHeadAndJson() =
    assertEquals("Bash: ls /tmp\n" + """{"command":"ls /tmp","description":"d"}""",
      ToolSummary.format("Bash", """{"command":"ls \/tmp","description":"d"}"""))
  @Test fun formatOmitsDuplicateBody() = assertEquals("Foo: {\"a\":1}", ToolSummary.format("Foo", """{"a":1}"""))
  @Test fun formatTruncatesJson() {
    val f = ToolSummary.format("Write", """{"file_path":"/a","content":"${"y".repeat(10000)}"}""")
    val body = f.substringAfter('\n')
    assertEquals(ToolSummary.MAX_INPUT + 1, body.length)
    assertTrue(body.endsWith("…"))
    assertEquals("Write: /a", f.substringBefore('\n'))
  }
}
