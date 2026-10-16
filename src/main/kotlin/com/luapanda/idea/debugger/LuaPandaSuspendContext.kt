package com.luapanda.idea.debugger

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import com.luapanda.idea.protocol.PathResolver
import com.luapanda.idea.protocol.StackFrameInfo

/**
 * Suspend context created when the debugger pauses (breakpoint hit, step completed, etc.).
 * Provides the active execution stack.
 */
class LuaPandaSuspendContext(
    private val debugProcess: LuaPandaDebugProcess,
    private val frames: List<StackFrameInfo>,
    private val pathResolver: PathResolver
) : XSuspendContext() {

    private val executionStack = LuaPandaExecutionStack(debugProcess, frames, pathResolver)

    override fun getActiveExecutionStack(): XExecutionStack = executionStack

    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(executionStack)
}
