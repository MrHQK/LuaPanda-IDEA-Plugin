package com.luapanda.idea.debugger

import com.intellij.lang.Language
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.evaluation.EvaluationMode

/**
 * Minimal implementation of [XExpression] built only on public API.
 *
 * The platform's `XExpressionImpl.fromText()` helper lives in
 * `com.intellij.xdebugger.impl.breakpoints`, which is internal and therefore rejected by
 * the Plugin Verifier. Any [XExpression] is just the raw text plus an evaluation mode, an
 * optional language and a custom-info string, so a plain value class is enough here: the
 * debugger always sends the expression text to LuaPanda verbatim.
 */
class LuaXExpression(
    private val text: String,
    private val mode: EvaluationMode = EvaluationMode.EXPRESSION
) : XExpression {

    override fun getExpression(): String = text

    override fun getMode(): EvaluationMode = mode

    override fun getLanguage(): Language? = null

    override fun getCustomInfo(): String? = null

    override fun toString(): String = text
}
