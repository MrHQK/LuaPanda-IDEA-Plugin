package com.luapanda.idea.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpointType

/**
 * Defines the breakpoint type for Lua files in LuaPanda debugger.
 *
 * IMPORTANT: Uses a unique ID "luapanda-line" to avoid conflict with EmmyLua's "lua-line" breakpoint type.
 * When EmmyLua is installed and its breakpoint type is available, this type yields (returns false from canPutAt)
 * to avoid duplicate breakpoint markers. LuaPanda's BreakpointHandler still works because it processes
 * any XLineBreakpoint on .lua files, regardless of which breakpoint type created them.
 */
class LuaPandaBreakpointType :
    XLineBreakpointType<LuaPandaBreakpointProperties>("luapanda-line", "LuaPanda Line Breakpoint") {

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.extension?.lowercase() != "lua") return false

        // If EmmyLua's breakpoint type ("lua-line") is registered, let EmmyLua handle breakpoints
        // to avoid duplicate breakpoint gutter icons. Our BreakpointHandler can still process them.
        val emmyBpType = XBreakpointType.EXTENSION_POINT_NAME.extensions.any {
            it.id == "lua-line"
        }
        return !emmyBpType
    }

    override fun createBreakpointProperties(file: VirtualFile, line: Int): LuaPandaBreakpointProperties {
        return LuaPandaBreakpointProperties()
    }
}
