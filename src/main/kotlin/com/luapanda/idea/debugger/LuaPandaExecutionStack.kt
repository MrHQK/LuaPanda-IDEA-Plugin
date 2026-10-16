package com.luapanda.idea.debugger

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.luapanda.idea.protocol.PathResolver
import com.luapanda.idea.protocol.StackFrameInfo

/**
 * Represents the execution stack (call stack) of a Lua coroutine/thread.
 */
class LuaPandaExecutionStack(
    private val debugProcess: LuaPandaDebugProcess,
    private val frames: List<StackFrameInfo>,
    private val pathResolver: PathResolver
) : XExecutionStack("Lua") {

    private val stackFrames: List<LuaPandaStackFrame> = frames.map { frame ->
        LuaPandaStackFrame(debugProcess, frame, pathResolver)
    }

    override fun getTopFrame(): XStackFrame? = stackFrames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        if (container == null) return
        val subList = if (firstFrameIndex < stackFrames.size) {
            stackFrames.subList(firstFrameIndex, stackFrames.size)
        } else {
            emptyList()
        }
        container.addStackFrames(subList, true)
    }
}
