package cn.elonzh.hanppie.agent.runtime

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

class StreamFrameToolCallTest {
    private fun complete(name: String, content: String, id: String = "call-1") = listOf(
        StreamFrame.ToolCallComplete(id = id, name = name, content = content),
        StreamFrame.End(finishReason = "stop"),
    )

    @Test fun emptyArgumentDocumentWouldBreakResponseMaterialization() {
        // The provider behaviour this guards against: an empty argument document cannot be parsed as JSON.
        // Koog only defaults the arguments when the provider omits the field entirely (StreamFrameFlowBuilder),
        // so an explicitly empty document stays empty and fails here.
        val failure = assertFailsWith<SerializationException> {
            complete("get_robot_status", "").toMessageResponse()
        }
        assertTrue(failure.message.orEmpty().contains("unexpected end of the input"),
            "expected the empty-document failure, got: ${failure.message}")
    }

    @Test fun blankArgumentsBecomeAnEmptyObject() {
        val call = complete("get_robot_status", "").withUsableToolCalls().toMessageResponse()
            .parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("get_robot_status", call.tool)
        assertEquals("{}", call.argsJson.toString())
    }

    @Test fun aNameThatOnlyArrivedInTheDeltasIsRestored() {
        val frames = listOf(
            StreamFrame.ToolCallDelta(id = "call-1", name = "get_robot_status", content = "{}"),
            StreamFrame.ToolCallComplete(id = "call-1", name = "", content = ""),
            StreamFrame.End(finishReason = "stop"),
        )
        val call = frames.withUsableToolCalls().toMessageResponse()
            .parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("get_robot_status", call.tool)
        assertEquals("{}", call.argsJson.toString())
    }

    @Test fun aNameArrivingInALaterDeltaWithoutAnIdIsStillMatched() {
        // DashScope puts the name on the opening delta; other providers drop the id after the first chunk and
        // keep the index. The name lookup must survive both, or a real call is mistaken for a phantom one.
        val frames = listOf(
            StreamFrame.ToolCallDelta(id = "call-1", name = "", content = "", index = 0),
            StreamFrame.ToolCallDelta(id = null, name = "get_robot_status", content = "{}", index = 0),
            StreamFrame.ToolCallComplete(id = "call-1", name = "", content = "", index = 0),
            StreamFrame.End(finishReason = "stop"),
        )
        val call = frames.withUsableToolCalls().toMessageResponse()
            .parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("get_robot_status", call.tool)
        assertEquals("{}", call.argsJson.toString())
    }

    @Test fun truncatedArgumentJsonFallsBackToAnEmptyObject() {
        val call = complete("get_robot_status", """{"fast":""").withUsableToolCalls().toMessageResponse()
            .parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("{}", call.argsJson.toString())
    }

    @Test fun aToolCallThatNeverNamedAToolIsDroppedInsteadOfAbortingTheTurn() {
        val frames = listOf(
            StreamFrame.ToolCallDelta(id = "call-1", name = "", content = ""),
            StreamFrame.ToolCallComplete(id = "call-1", name = "", content = ""),
            StreamFrame.TextComplete(text = "机器人已经就位"),
            StreamFrame.End(finishReason = "stop"),
        )
        val response = frames.withUsableToolCalls().toMessageResponse()
        assertTrue(response.parts.none { it is MessagePart.Tool.Call }, "the phantom call must not be executed")
        assertTrue(response.parts.any { it is MessagePart.Text && it.text.contains("机器人") })
    }
}
