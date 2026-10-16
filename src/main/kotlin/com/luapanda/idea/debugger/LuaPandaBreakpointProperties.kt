package com.luapanda.idea.debugger

import com.intellij.xdebugger.breakpoints.XBreakpointProperties

/**
 * Breakpoint properties for LuaPanda breakpoints.
 * Currently empty — condition and log expression are stored in the XBreakpoint itself.
 */
class LuaPandaBreakpointProperties : XBreakpointProperties<LuaPandaBreakpointProperties>() {
    override fun getState(): LuaPandaBreakpointProperties = this
    override fun loadState(state: LuaPandaBreakpointProperties) {}
}
