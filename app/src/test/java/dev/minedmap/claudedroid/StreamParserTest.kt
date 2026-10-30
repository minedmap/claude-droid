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
  @Test fun delta() = assertEquals(listOf(UiEvent.Delta("he")),
    StreamParser.parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"he"}}}"""))
  @Test fun otherStreamEventIgnored() = assertEquals(emptyList<UiEvent>(),
    StreamParser.parse("""{"type":"stream_event","event":{"type":"message_start"}}"""))
  @Test fun cost() = assertEquals(listOf(UiEvent.Result("ok", 0.0123)),
    StreamParser.parse("""{"type":"result","result":"ok","total_cost_usd":0.0123}"""))
  @Test fun garbage() = assertEquals(listOf(UiEvent.Raw("not json")), StreamParser.parse("not json"))
}
