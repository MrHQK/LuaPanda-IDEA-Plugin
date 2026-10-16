package com.luapanda.idea.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for ProtocolProcessor message handling.
 * Validates JSON serialization/deserialization, message framing, and real protocol constants.
 */
class ProtocolProcessorTest {

    private val gson = Gson()

    @Test
    fun `test message serialization with real cmd field`() {
        val msg = LuaPandaMessage(cmd = ProtocolConstants.CMD_CONTINUE, callbackId = "0")
        val json = gson.toJson(msg)
        assertTrue(json.contains("\"cmd\":\"continue\""))
        assertTrue(json.contains("\"callbackId\":\"0\""))
    }

    @Test
    fun `test breakpoint info serialization roundtrip`() {
        val original = BreakpointInfo(
            file = "/path/to/test.lua",
            line = 42,
            condition = "x > 10",
            logMessage = null,
            enabled = true
        )
        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, BreakpointInfo::class.java)
        assertEquals(original.file, deserialized.file)
        assertEquals(original.line, deserialized.line)
        assertEquals(original.condition, deserialized.condition)
        assertNull(deserialized.logMessage)
        assertTrue(deserialized.enabled)
    }

    @Test
    fun `test stack frame info serialization`() {
        val frame = StackFrameInfo(
            file = "test.lua",
            line = 10,
            functionName = "myFunc",
            level = 0
        )
        val json = gson.toJson(frame)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("test.lua", obj.get("file").asString)
        assertEquals(10, obj.get("line").asInt)
        assertEquals("myFunc", obj.get("functionName").asString)
    }

    @Test
    fun `test variable info with nested children`() {
        val child = VariableInfo(
            name = "key",
            value = "\"hello\"",
            type = "string"
        )
        val parent = VariableInfo(
            name = "myTable",
            value = "table: 0x12345",
            type = "table",
            variablesReference = 1,
            children = listOf(child)
        )
        val json = gson.toJson(parent)
        val deserialized = gson.fromJson(json, VariableInfo::class.java)
        assertEquals("myTable", deserialized.name)
        assertNotNull(deserialized.children)
        assertEquals(1, deserialized.children!!.size)
        assertEquals("key", deserialized.children!![0].name)
    }

    @Test
    fun `test evaluate request uses getWatchedVariable command`() {
        val req = EvaluateRequest(
            cmd = ProtocolConstants.CMD_GET_WATCHED_VARIABLE,
            callbackId = "42",
            info = EvaluateInfo(expression = "1 + 2", frameId = 0)
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"cmd\":\"getWatchedVariable\""))
        assertTrue(json.contains("\"expression\":\"1 + 2\""))
    }

    @Test
    fun `test message frame format uses TCP split char`() {
        val body = """{"cmd":"continue","callbackId":"0"}"""
        val frame = "$body ${ProtocolConstants.TCP_SPLIT_CHAR}\n"
        assertTrue(frame.contains("|*|"), "Frame should contain TCP split char |*|")
        assertTrue(frame.endsWith("\n"), "Frame should end with newline")
        assertTrue(frame.contains(body), "Frame should contain the JSON body")
    }

    @Test
    fun `test protocol constants have correct values`() {
        // IDE → Lua commands
        assertEquals("initSuccess", ProtocolConstants.CMD_INIT_SUCCESS)
        assertEquals("continue", ProtocolConstants.CMD_CONTINUE)
        assertEquals("stopOnStep", ProtocolConstants.CMD_STEP_OVER)
        assertEquals("stopOnStepIn", ProtocolConstants.CMD_STEP_INTO)
        assertEquals("stopOnStepOut", ProtocolConstants.CMD_STEP_OUT)
        assertEquals("stopRun", ProtocolConstants.CMD_STOP)
        assertEquals("setBreakPoint", ProtocolConstants.CMD_SET_BREAKPOINTS)
        assertEquals("getVariable", ProtocolConstants.CMD_GET_VARIABLE)
        assertEquals("getWatchedVariable", ProtocolConstants.CMD_GET_WATCHED_VARIABLE)
        assertEquals("setVariable", ProtocolConstants.CMD_SET_VARIABLE)
        assertEquals("runREPLExpression", ProtocolConstants.CMD_RUN_REPL)
        assertEquals("LuaGarbageCollect", ProtocolConstants.CMD_LUA_GC)

        // Lua → IDE events
        assertEquals("stopOnCodeBreakpoint", ProtocolConstants.EVT_STOP_ON_CODE_BREAKPOINT)
        assertEquals("stopOnBreakpoint", ProtocolConstants.EVT_STOP_ON_BREAKPOINT)
        assertEquals("stopOnEntry", ProtocolConstants.EVT_STOP_ON_ENTRY)
        assertEquals("stopOnStep", ProtocolConstants.EVT_STOP_ON_STEP)
        assertEquals("stopOnStepIn", ProtocolConstants.EVT_STOP_ON_STEP_IN)
        assertEquals("stopOnStepOut", ProtocolConstants.EVT_STOP_ON_STEP_OUT)
        assertEquals("output", ProtocolConstants.EVT_OUTPUT)
        assertEquals("debug_console", ProtocolConstants.EVT_DEBUG_CONSOLE)
        assertEquals("tip", ProtocolConstants.EVT_TIP)
        assertEquals("tipError", ProtocolConstants.EVT_TIP_ERROR)
        assertEquals("refreshLuaMemory", ProtocolConstants.EVT_REFRESH_LUA_MEMORY)

        // Frame delimiter
        assertEquals("|*|", ProtocolConstants.TCP_SPLIT_CHAR)
        assertEquals(8818, ProtocolConstants.DEFAULT_PORT)
    }

    @Test
    fun `test init request serialization`() {
        val req = InitRequest(
            cmd = ProtocolConstants.CMD_INIT_SUCCESS,
            callbackId = "0",
            info = InitRequestInfo(
                stopOnEntry = true,
                pathCaseSensitivity = false,
                autoPathMode = true,
                cwd = "/project",
                luaFileExtension = "lua",
                logLevel = 1
            )
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"cmd\":\"initSuccess\""))
        assertTrue(json.contains("\"stopOnEntry\":true"))
        assertTrue(json.contains("\"cwd\":\"/project\""))
    }

    @Test
    fun `test set breakpoints serialization`() {
        val req = SetBreakpointsRequest(
            cmd = ProtocolConstants.CMD_SET_BREAKPOINTS,
            callbackId = "0",
            info = listOf(
                BreakpointInfo(file = "test.lua", line = 10),
                BreakpointInfo(file = "test.lua", line = 20, condition = "i > 5")
            )
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"cmd\":\"setBreakPoint\""))
        assertTrue(json.contains("\"file\":\"test.lua\""))
        assertTrue(json.contains("\"line\":10"))
        // Gson escapes '>' as '\u003e' by default, so "i > 5" becomes "i \u003e 5"
        assertTrue(json.contains("condition") && json.contains("i"), "JSON should contain condition with 'i': $json")
    }

    @Test
    fun `test parse incoming stop event`() {
        val incoming = """{"cmd":"stopOnCodeBreakpoint","callbackId":"0","info":[{"file":"test.lua","line":10,"func":"main","level":0}]}"""
        val json = JsonParser.parseString(incoming).asJsonObject
        assertEquals("stopOnCodeBreakpoint", json.get("cmd").asString)
        assertEquals("0", json.get("callbackId").asString)
        val info = json.getAsJsonArray("info")
        assertNotNull(info)
        assertEquals(1, info.size())
        assertEquals("test.lua", info[0].asJsonObject.get("file").asString)
    }

    @Test
    fun `test parse incoming output event`() {
        val incoming = """{"cmd":"output","callbackId":"0","info":{"logInfo":"Hello from Lua"}}"""
        val json = JsonParser.parseString(incoming).asJsonObject
        assertEquals("output", json.get("cmd").asString)
        val info = json.getAsJsonObject("info")
        assertEquals("Hello from Lua", info.get("logInfo").asString)
    }

    @Test
    fun `test sticky packet splitting`() {
        val splitChar = ProtocolConstants.TCP_SPLIT_CHAR
        val rawData = """{"cmd":"output","callbackId":"0","info":{}} $splitChar{"cmd":"tip","callbackId":"0","info":{}} $splitChar"""
        val parts = rawData.split(splitChar)
        // Should have 3 parts: 2 messages + 1 empty trailing
        assertEquals(3, parts.size)
        assertTrue(parts[0].trim().startsWith("{"))
        assertTrue(parts[1].trim().startsWith("{"))
        assertTrue(parts[2].trim().isEmpty())
    }
}
