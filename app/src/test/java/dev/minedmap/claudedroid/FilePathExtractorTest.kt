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
  @Test fun readImageOnly() {
    assertEquals(listOf("/a/x.png"), FilePathExtractor.fromToolUse("Read", """{"file_path":"/a/x.png"}"""))
    assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("Read", """{"file_path":"/a/x.kt"}"""))
  }
  @Test fun bashOutputs() = assertEquals(listOf("/tmp/screen.png", "/root/p/o.json", "/sdcard/s.png"),
    FilePathExtractor.fromToolUse("Bash", """{"command":"adb exec-out screencap -p > /tmp/screen.png 2>&1; curl -o o.json x; adb shell screencap -p /sdcard/s.png; ls /x/y.png"}""", "/root/p"))
  @Test fun bashNoOutput() = assertEquals(emptyList<String>(), FilePathExtractor.fromToolUse("Bash", """{"command":"ls -la > /dev/null"}"""))
  @Test fun koreanAndSpaces() = assertEquals(listOf("/sdcard/사진/스크린샷.png", "/root/my file.md"),
    FilePathExtractor.fromText("저장: /sdcard/사진/스크린샷.png 그리고 `/root/my file.md`"))
  @Test fun tildeAndRelativeWithCwd() = assertEquals(listOf("/root/foo.png", "/root/proj/out/a.png"),
    FilePathExtractor.fromText("~/foo.png and out/a.png", "/root/proj"))
  @Test fun tildeTermuxHome() = assertEquals(listOf("/data/data/com.termux/files/home/a.md"), FilePathExtractor.fromText("~/a.md"))
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
