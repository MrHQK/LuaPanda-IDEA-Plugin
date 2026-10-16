package com.luapanda.idea.debugger

import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * Program runner for LuaPanda debugging.
 * Handles the "Debug" executor to create and start XDebugProcess sessions.
 */
class LuaPandaProgramRunner : GenericProgramRunner<com.intellij.execution.configurations.RunnerSettings>() {

    override fun getRunnerId(): String = "LuaPandaProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        return executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is LuaPandaRunConfiguration
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val config = environment.runProfile as? LuaPandaRunConfiguration ?: return null

        val session = XDebuggerManager.getInstance(environment.project)
            .startSession(environment, object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    return LuaPandaDebugProcess(session, config)
                }
            })

        return session.runContentDescriptor
    }
}
