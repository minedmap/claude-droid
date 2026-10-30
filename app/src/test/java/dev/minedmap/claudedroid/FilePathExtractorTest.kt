package dev.minedmap.claudedroid
import dev.minedmap.claudedroid.net.FilePathExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
class FilePathExtractorTest {
  @Test fun writeAnyExt() = assertEquals(listOf("/a/b/c.kt"),
    FilePathExtractor.fromToolUse("Write", """{"file_path":"/a/b/c.kt","content":"x"}"""))
  @Test fun editAndNotebook() {
    assertEquals(listOf("/a/x.md"), FilePathExtractor.fromToolUse("Edit", """{"file_path":"/a/x.md"}"""))
    assertEquals(listOf("/a/n.ipynb"), FilePathExtractor.fromToolUse("NotebookEdit", """{"notebook_path":"/a/n.ipynb"}"""))
  }
  @Test fun readSkipped() = assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("Read", """{"file_path":"/a/x.png"}"""))
  @Test fun mcpFields() = assertEquals(listOf("/s/shot.png", "/s/out.json"),
    FilePathExtractor.fromToolUse("mcp__x__screenshot", """{"output_path":"/s/shot.png","path":"/s/out.json"}""").sortedByDescending { it.endsWith("png") })
  @Test fun mcpUnknownExtIgnored() = assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("mcp__x__y", """{"path":"/s/a.bin"}"""))
  @Test fun relativeIgnored() = assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("Write", """{"file_path":"a.txt"}"""))
  @Test fun badJson() = assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("Write", "nope"))
  @Test fun textPaths() = assertEquals(listOf("/tmp/a.png", "/root/r_1.md"),
    FilePathExtractor.fromText("saved `/tmp/a.png` and (/root/r_1.md). again /tmp/a.png"))
  @Test fun textIgnoresUrlsRelativeAndExt() = assertEquals(listOf("/ok/x.json"),
    FilePathExtractor.fromText("https://e.com/a/b.png ./rel/c.png src/d.png /e/f.pngx /ok/x.json"))
}
