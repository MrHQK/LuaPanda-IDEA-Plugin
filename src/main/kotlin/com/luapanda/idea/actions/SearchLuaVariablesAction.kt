package com.luapanda.idea.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.luapanda.idea.debugger.LuaPandaDebugProcess
import com.luapanda.idea.debugger.search.LuaVariableSearchDialog

/**
 * Opens the "Search Lua Variables" dialog for the current LuaPanda debug session.
 * Enabled only while the session is paused (breakpoint / step).
 */
class SearchLuaVariablesAction : DumbAwareAction(
    "Search Lua Variables...",
    "Search variables of the current stack frame by name",
    AllIcons.Actions.Find
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val session = findSession(e)
        val isLua = session?.debugProcess is LuaPandaDebugProcess
        e.presentation.isVisible = isLua
        e.presentation.isEnabled = isLua && session!!.isSuspended
    }

    override fun actionPerformed(e: AnActionEvent) {
        val session = findSession(e) ?: return
        val process = session.debugProcess as? LuaPandaDebugProcess ?: return
        LuaVariableSearchDialog.showFor(session, process)
    }

    private fun findSession(e: AnActionEvent): XDebugSession? {
        e.getData(XDebugSession.DATA_KEY)?.let { return it }
        val project = e.project ?: return null
        return XDebuggerManager.getInstance(project).currentSession
    }

    companion object {
        const val ACTION_ID = "LuaPanda.SearchVariables"
    }
}
