package com.luapanda.idea.protocol

/**
 * Protocol messages for LuaPanda TCP/JSON communication.
 *
 * LuaPanda uses a custom message framing format:
 *   Message = JSON_BODY + " " + TCPSplitChar + "\n"
 *   where TCPSplitChar = "|*|"
 *
 * The JSON body typically has the format:
 *   { "cmd": "<command>", "callbackId": "<id>", "info": { ... } }
 *
 * Protocol reference: Tencent/LuaPanda (GitHub)
 *   - Lua side: LuaPanda.lua -> dataProcess()
 *   - VSCode side: dataProcessor.ts -> commandToDebugger() / getData()
 *
 * This file defines Kotlin data classes for all known protocol messages,
 * maintaining full compatibility with the original LuaPanda.lua debugger.
 */

// ==================== Message Envelope ====================

data class LuaPandaMessage(
    val cmd: String,
    val callbackId: String = "0",
    val info: Any? = null
)

// ==================== Debugger Commands (IDE → Lua) ====================

/** Continue execution */
data class ContinueRequest(
    val cmd: String = ProtocolConstants.CMD_CONTINUE,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Step over (next line) */
data class StepOverRequest(
    val cmd: String = ProtocolConstants.CMD_STEP_OVER,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Step into */
data class StepIntoRequest(
    val cmd: String = ProtocolConstants.CMD_STEP_INTO,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Step out - uses stopOnStep with stepOut semantics (handled by LuaPanda.lua internal state) */
data class StepOutRequest(
    val cmd: String = ProtocolConstants.CMD_STEP_OUT,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Stop debugging */
data class StopRequest(
    val cmd: String = ProtocolConstants.CMD_STOP,
    val callbackId: String = "0",
    val info: Any? = null
)

// ==================== Breakpoint Messages ====================

data class BreakpointInfo(
    val file: String,
    val line: Int,
    val condition: String? = null,
    val logMessage: String? = null,
    val enabled: Boolean = true
)

data class SetBreakpointsRequest(
    val cmd: String = ProtocolConstants.CMD_SET_BREAKPOINTS,
    val callbackId: String = "0",
    val info: List<BreakpointInfo> = emptyList()
)

// ==================== Stack / Variable Messages ====================

data class StackFrameInfo(
    val file: String,
    val line: Int,
    val functionName: String = "",
    val level: Int = 0
)

data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val variablesReference: Int = 0,
    val children: List<VariableInfo>? = null
)

data class VariablesResponse(
    val cmd: String = "",
    val callbackId: String = "0",
    val info: List<VariableInfo> = emptyList()
)

// ==================== Evaluate ====================

data class EvaluateRequest(
    val cmd: String = ProtocolConstants.CMD_GET_WATCHED_VARIABLE,
    val callbackId: String = "0",
    val info: EvaluateInfo? = null
)

data class EvaluateInfo(
    val expression: String = "",
    val frameId: Int = 0
)

data class EvaluateResponse(
    val cmd: String = "",
    val callbackId: String = "0",
    val info: List<VariableInfo> = emptyList()
)

// ==================== Init / Handshake ====================

data class InitRequestInfo(
    val stopOnEntry: Boolean = false,
    val pathCaseSensitivity: Boolean = true,
    val autoPathMode: Boolean = true,
    val cwd: String = "",
    val luaFileExtension: String = "",
    val logLevel: Int = 1
)

data class InitRequest(
    val cmd: String = ProtocolConstants.CMD_INIT_SUCCESS,
    val callbackId: String = "0",
    val info: InitRequestInfo? = null
)

// ==================== Events (Lua → IDE) ====================

/** Breakpoint hit event (code breakpoint) */
data class StopOnCodeBreakpointEvent(
    val cmd: String = ProtocolConstants.EVT_STOP_ON_CODE_BREAKPOINT,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Breakpoint hit event (manual BP() call) */
data class StopOnBreakpointEvent(
    val cmd: String = ProtocolConstants.EVT_STOP_ON_BREAKPOINT,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Stop on entry event */
data class StopOnEntryEvent(
    val cmd: String = ProtocolConstants.EVT_STOP_ON_ENTRY,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Output event (console output from Lua side) */
data class OutputEvent(
    val cmd: String = ProtocolConstants.EVT_OUTPUT,
    val callbackId: String = "0",
    val info: OutputInfo? = null
)

data class OutputInfo(
    val output: String = "",
    val category: String = "console"
)

/** Tip event (notification from Lua side) */
data class TipEvent(
    val cmd: String = ProtocolConstants.EVT_TIP,
    val callbackId: String = "0",
    val info: Any? = null
)

/** Memory refresh event */
data class RefreshMemoryEvent(
    val cmd: String = ProtocolConstants.EVT_REFRESH_LUA_MEMORY,
    val callbackId: String = "0",
    val info: Any? = null
)

// ==================== Protocol Constants ====================

object ProtocolConstants {
    // Commands sent from IDE to Lua (from luaDebugRuntime.ts -> commandToDebugger)
    const val CMD_INIT_SUCCESS = "initSuccess"           // Initialize debugger session
    const val CMD_CONTINUE = "continue"                  // Continue execution
    const val CMD_STEP_OVER = "stopOnStep"               // Step over (next line)
    const val CMD_STEP_INTO = "stopOnStepIn"             // Step into function
    const val CMD_STEP_OUT = "stopOnStepOut"             // Step out of function
    const val CMD_STOP = "stopRun"                       // Stop debugging
    const val CMD_SET_BREAKPOINTS = "setBreakPoint"      // Set breakpoints
    const val CMD_GET_VARIABLE = "getVariable"           // Get variable info
    const val CMD_GET_WATCHED_VARIABLE = "getWatchedVariable" // Get watched variable / evaluate
    const val CMD_SET_VARIABLE = "setVariable"           // Set variable value
    const val CMD_RUN_REPL = "runREPLExpression"         // Execute REPL expression
    const val CMD_LUA_GC = "LuaGarbageCollect"           // Force Lua garbage collection

    // Events from Lua to IDE (from LuaPanda.lua -> sendMsg / dataProcessor.ts -> getData)
    const val EVT_STOP_ON_CODE_BREAKPOINT = "stopOnCodeBreakpoint" // Hit a code line breakpoint
    const val EVT_STOP_ON_BREAKPOINT = "stopOnBreakpoint"          // Hit a manual BP() breakpoint
    const val EVT_STOP_ON_ENTRY = "stopOnEntry"                    // Stopped at program entry
    const val EVT_STOP_ON_STEP = "stopOnStep"                      // Stopped after step over
    const val EVT_STOP_ON_STEP_IN = "stopOnStepIn"                 // Stopped after step in
    const val EVT_STOP_ON_STEP_OUT = "stopOnStepOut"               // Stopped after step out
    const val EVT_OUTPUT = "output"                                // Console output
    const val EVT_DEBUG_CONSOLE = "debug_console"                  // Debug console output
    const val EVT_TIP = "tip"                                      // Info tip notification
    const val EVT_TIP_ERROR = "tipError"                           // Error tip notification
    const val EVT_REFRESH_LUA_MEMORY = "refreshLuaMemory"          // Memory refresh info

    // TCP message frame delimiter (from LuaPanda.lua: TCPSplitChar = "|*|")
    const val TCP_SPLIT_CHAR = "|*|"

    // Default port
    const val DEFAULT_PORT = 8818
}
