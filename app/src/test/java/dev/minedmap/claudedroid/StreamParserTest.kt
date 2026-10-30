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
  @Test fun resultIsError() = assertEquals(listOf(UiEvent.Result("boom", 0.0, true)),
    StreamParser.parse("""{"type":"result","is_error":true,"result":"boom","total_cost_usd":0}"""))
  @Test fun resultErrorSubtypeWithoutText() = assertEquals(listOf(UiEvent.Result("error_max_turns", null, true)),
    StreamParser.parse("""{"type":"result","subtype":"error_max_turns"}"""))
  @Test fun resultSuccessNotError() = assertEquals(listOf(UiEvent.Result("ok", null, false)),
    StreamParser.parse("""{"type":"result","subtype":"success","is_error":false,"result":"ok"}"""))
  @Test fun resultNullCost() = assertEquals(listOf(UiEvent.Result("ok", null)),
    StreamParser.parse("""{"type":"result","result":"ok","total_cost_usd":null}"""))
  @Test fun toolResultImage() = assertEquals(listOf(UiEvent.ToolResult("cap"), UiEvent.ToolImage("image/jpeg", "QUJD")),
    StreamParser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","content":[{"type":"text","text":"cap"},{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"QUJD"}}]}]}}"""))
  @Test fun imageOnlyNoEmptyBubble() = assertEquals(listOf(UiEvent.ToolImage("image/png", "QQ==")),
    StreamParser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"QQ=="}}]}]}}"""))
  @Test fun emptyResultSkipped() = assertEquals(emptyList<UiEvent>(),
    StreamParser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","content":""}]}}"""))
  @Test fun isError() = assertEquals(listOf(UiEvent.ToolResult("boom", true)),
    StreamParser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"boom"}]}}"""))
  @Test fun missingFieldsNoThrow() {
    assertEquals(emptyList<UiEvent>(), StreamParser.parse("""{"type":"system","subtype":"init"}"""))
    assertEquals(listOf(UiEvent.ToolUse("?", "{}")), StreamParser.parse("""{"type":"assistant","message":{"content":[{"type":"text"},{"type":"tool_use"}]}}"""))
  }
  @Test fun garbage() = assertEquals(listOf(UiEvent.Raw("not json")), StreamParser.parse("not json"))
}
