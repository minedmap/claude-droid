package dev.minedmap.claudedroid
import dev.minedmap.claudedroid.net.StreamParser
import dev.minedmap.claudedroid.net.UiEvent
import org.junit.Assert.assertEquals
import org.junit.Test
class StreamParserTest {
  @Test fun init() = assertEquals(listOf(UiEvent.SessionStarted("s1")),
    StreamParser.parse("""{"type":"system","subtype":"init","session_id":"s1"}"""))
  @Test fun multiBlock() = assertEquals(
    listOf(UiEvent.AssistantText("hi"), UiEvent.ToolUse("Bash", """{"command":"ls"}""")),
    StreamParser.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"hi"},{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}"""))
  @Test fun toolResult() = assertEquals(listOf(UiEvent.ToolResult("a.txt")),
    StreamParser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","content":"a.txt"}]}}"""))
  @Test fun done() = assertEquals(listOf(UiEvent.Done(0, "")),
    StreamParser.parse("""{"type":"done","code":0,"stderr":""}"""))
  @Test fun unknownIgnored() = assertEquals(emptyList<UiEvent>(), StreamParser.parse("""{"type":"zzz"}"""))
  @Test fun garbage() = assertEquals(listOf(UiEvent.Raw("not json")), StreamParser.parse("not json"))
}
