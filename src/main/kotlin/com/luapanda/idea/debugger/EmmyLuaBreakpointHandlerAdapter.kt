package com.luapanda.idea.debugger

import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Adapter handler that listens for breakpoints of EmmyLua's type ("lua-line").
 * This allows LuaPanda's debugger to intercept breakpoints created by EmmyLua
 * when the user clicks the gutter in .lua files.
 */
class EmmyLuaBreakpointHandlerAdapter(
    private val debugProcess: LuaPandaDebugProcess,
    breakpointTypeClass: Class<out XBreakpointType<XLineBreakpoint<*>, *>>
) : XBreakpointHandler<XLineBreakpoint<*>>(breakpointTypeClass) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<*>) {
        debugProcess.registerBreakpoint(breakpoint)
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<*>, temporary: Boolean) {
        debugProcess.unregisterBreakpoint(breakpoint)
    }
}
