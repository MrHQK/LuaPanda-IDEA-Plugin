package com.luapanda.idea.debugger

import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Handles breakpoint registration and unregistration for LuaPanda's own breakpoint type.
 * This handler is used when EmmyLua is NOT installed (i.e., LuaPanda provides the breakpoint gutter).
 *
 * When EmmyLua IS installed, the EmmyLuaBreakpointHandlerAdapter handles breakpoints
 * created by EmmyLua's "lua-line" type. Both handlers forward to LuaPandaDebugProcess.
 */
class LuaPandaBreakpointHandler(
    private val debugProcess: LuaPandaDebugProcess
) : XBreakpointHandler<XLineBreakpoint<LuaPandaBreakpointProperties>>(LuaPandaBreakpointType::class.java) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<LuaPandaBreakpointProperties>) {
        debugProcess.registerBreakpoint(breakpoint)
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<LuaPandaBreakpointProperties>, temporary: Boolean) {
        debugProcess.unregisterBreakpoint(breakpoint)
    }
}
