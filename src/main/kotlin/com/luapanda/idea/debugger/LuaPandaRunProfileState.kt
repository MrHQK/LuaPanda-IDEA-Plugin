package com.luapanda.idea.debugger

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project

/**
 * Run profile state for LuaPanda debugging.
 */
class LuaPandaRunProfileState(
    private val config: LuaPandaRunConfiguration,
    private val environment: ExecutionEnvironment
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = LuaPandaProcessHandler()
        processHandler.startNotify()
        return DefaultExecutionResult(null, processHandler)
    }
}
