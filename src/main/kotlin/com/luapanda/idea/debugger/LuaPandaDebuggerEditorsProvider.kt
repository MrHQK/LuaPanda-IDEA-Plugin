package com.luapanda.idea.debugger

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * Provides editor support for the Evaluate Expression dialog.
 *
 * Uses PlainTextFileType instead of LuaFileType to avoid conflict with EmmyLua.
 * The evaluate expression dialog works with plain text since we're sending
 * the expression as a string to the LuaPanda debugger anyway.
 */
class LuaPandaDebuggerEditorsProvider : XDebuggerEditorsProvider() {

    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode
    ): Document {
        // Public API. (DocumentImpl lives in com.intellij.openapi.editor.impl and is
        // internal — the Plugin Verifier rejects plugins that reference it.)
        return EditorFactory.getInstance().createDocument(expression.expression)
    }
}
