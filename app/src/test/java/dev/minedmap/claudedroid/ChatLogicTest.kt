package dev.minedmap.claudedroid
import dev.minedmap.claudedroid.net.ActiveRun
import dev.minedmap.claudedroid.net.ChatLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class ChatLogicTest {
  @Test fun modelValid() {
    for (m in listOf("opus", "claude-opus-5-5", "claude-sonnet-4.5[1m]", "a_b.c-d", "x".repeat(64)))
      assertTrue(m, ChatLogic.isValidModel(m))
  }
  @Test fun modelInvalid() {
    for (m in listOf("", " ", "a b", "x".repeat(65), "a;b", "a/b", "한글", "a\n", "$(x)", "a\"b"))
      assertFalse(m, ChatLogic.isValidModel(m))
  }
  @Test fun noConversation() {
    assertTrue(ChatLogic.isNoConversation("", "Error: No conversation found with session ID abc"))
    assertTrue(ChatLogic.isNoConversation(null, "no conversation found"))
    assertFalse(ChatLogic.isNoConversation("fine", null))
  }
  @Test fun costLabel() {
    assertNull(ChatLogic.costLabel(null))
    assertNull(ChatLogic.costLabel(0.0))
    assertNull(ChatLogic.costLabel(Double.NaN))
    assertEquals("💰 $0.0123", ChatLogic.costLabel(0.0123))
  }
  @Test fun doneError() {
    assertNull(ChatLogic.doneError(0, "x", false, false))
    assertEquals("exit 2", ChatLogic.doneError(2, "", false, false))
    assertEquals("boom", ChatLogic.doneError(1, "boom", false, false))
    assertNull(ChatLogic.doneError(1, "boom", true, false))   // error already shown
    assertNull(ChatLogic.doneError(143, "", false, true))     // user stopped
  }
  @Test fun errorMessage() {
    assertEquals("invalid model (HTTP 400)", ChatLogic.errorMessage("""{"error":"invalid model"}""", 400))
    assertEquals("HTTP 502", ChatLogic.errorMessage("<html>bad gateway</html>", 502))
    assertEquals("HTTP 404", ChatLogic.errorMessage(null, 404))
    assertEquals("HTTP 400", ChatLogic.errorMessage("""{"error":""}""", 400))
  }
  @Test fun backoff() {
    assertEquals(1000L, ChatLogic.backoffMs(0))
    assertEquals(4000L, ChatLogic.backoffMs(2))
    assertEquals(8000L, ChatLogic.backoffMs(3))
    assertEquals(8000L, ChatLogic.backoffMs(50))
  }
  @Test fun activeRunCodec() {
    assertEquals(ActiveRun("r-1", 7), ActiveRun.decode(ActiveRun.encode(ActiveRun("r-1", 7))))
    assertEquals(ActiveRun("a|b", -1), ActiveRun.decode(ActiveRun.encode(ActiveRun("a|b", -1))))
    assertNull(ActiveRun.decode(null)); assertNull(ActiveRun.decode("")); assertNull(ActiveRun.decode("nobar")); assertNull(ActiveRun.decode("r|x"))
  }
}
