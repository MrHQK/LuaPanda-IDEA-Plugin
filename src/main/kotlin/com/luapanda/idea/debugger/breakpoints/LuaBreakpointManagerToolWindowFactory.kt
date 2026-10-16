package com.luapanda.idea.debugger.breakpoints

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Tool Window Factory for the LuaPanda Breakpoint Manager.
 *
 * Registers a tool window that displays all Lua breakpoints in the project,
 * providing navigation, enable/disable, and bulk management operations.
 *
 * The tool window appears in the bottom panel alongside Debug/Terminal windows.
 */
class LuaBreakpointManagerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LuaBreakpointManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Breakpoints", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
