package com.luapanda.idea.debugger

import com.intellij.execution.process.ProcessHandler
import java.io.OutputStream

/**
 * Process handler for the LuaPanda debug session.
 * Manages process lifecycle signals for IDE status display.
 */
class LuaPandaProcessHandler : ProcessHandler() {

    override fun destroyProcessImpl() {
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
