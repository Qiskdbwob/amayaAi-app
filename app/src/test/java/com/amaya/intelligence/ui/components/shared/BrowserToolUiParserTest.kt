package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class BrowserToolUiParserTest {
    @Test
    fun `browser parent becomes an expandable child group`() {
        val parent = ToolExecution(
            toolCallId = "browser_1",
            name = "browser",
            arguments = emptyMap(),
            result = """{"tool":"browser","sub_toolcalls":[{"id":"step_1","tool":"browser.open_url","status":"success","response":{"request":{"params":{"url":"https://example.com"}},"result":{"page":{"title":"Example","url":"https://example.com"}}}}]}""", 
            status = ToolStatus.SUCCESS,
            metadata = mapOf("source" to "local")
        )

        val group = assertNotNull(
            actual = buildToolExecutionGroups(listOf(MessageStep.ToolCall(execution = parent))).singleOrNull()
        )
        assertEquals("browser_1", group.parentToolCallId)
        assertEquals(1, group.executions.size)
        assertEquals("open_url", group.executions.single().name)
        assertEquals("https://example.com", group.executions.single().arguments["url"])
        assertEquals("## Page state\nExample\nhttps://example.com", group.executions.single().result)
    }

    @Test
    fun `expression alias renders JavaScript output`() {
        val parent = ToolExecution(
            toolCallId = "browser_2",
            name = "browser",
            arguments = emptyMap(),
            result = """{"tool":"browser","sub_toolcalls":[{"id":"step_2","tool":"browser.expression","status":"success","response":{"request":{"params":{"script":"document.links.length"}},"result":{"value":42}}}]}""",
            status = ToolStatus.SUCCESS,
            metadata = mapOf("source" to "local")
        )

        val execution = assertNotNull(synthesizeBrowserGroup(parent, 0)).executions.single()
        assertEquals("Run expression", execution.uiMetadata?.label)
        assertEquals("document.links.length", execution.arguments["script"])
        assertEquals("## Output\n```javascript\n42\n```", execution.result)
    }

    @Test
    fun `error exposes readable next step`() {
        val parent = ToolExecution(
            toolCallId = "browser_3",
            name = "browser",
            arguments = emptyMap(),
            result = """{"tool":"browser","sub_toolcalls":[{"id":"step_3","tool":"browser.evaluate","status":"error","response":{"request":{"params":{"script":"bad()"}},"error":{"code":"BROWSER_ACTION_FAILED","message":"Unknown browser tool: expression","suggested_action":"inspect_page_and_retry"}}}]}""",
            status = ToolStatus.ERROR,
            metadata = mapOf("source" to "local")
        )

        val result = assertNotNull(
            actual = assertNotNull(synthesizeBrowserGroup(parent, 0)).executions.single().result
        )
        assertTrue(result.contains("## Error\n**BROWSER_ACTION_FAILED**"))
        assertTrue(result.contains("## Next step\ninspect page and retry"))
    }
}
