package com.luapanda.idea.debugger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.ExpressionInfo
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.luapanda.idea.LuaPandaIcons
import com.luapanda.idea.protocol.PathResolver
import com.luapanda.idea.protocol.StackFrameInfo
import javax.swing.Icon

/**
 * Represents a single stack frame in the Lua call stack.
 * Provides source position, variable inspection, and expression evaluation.
 */
class LuaPandaStackFrame(
    private val debugProcess: LuaPandaDebugProcess,
    private val frameInfo: StackFrameInfo,
    private val pathResolver: PathResolver
) : XStackFrame() {

    private val logger = Logger.getInstance(LuaPandaStackFrame::class.java)

    /** LuaPanda stackId of this frame (used by getVariable / getWatchedVariable). */
    val frameLevel: Int get() = frameInfo.level

    /** Human-readable frame description, e.g. `update (main.lua:42)`. */
    val displayName: String
        get() {
            val funcName = frameInfo.functionName.ifEmpty { "<anonymous>" }
            val fileName = frameInfo.file.substringAfterLast('/').substringAfterLast('\\')
            return "$funcName ($fileName:${frameInfo.line})"
        }

    /**
     * Cached source position. IntelliJ's framework calls getSourcePosition() multiple times
     * per stack frame (typically 4× per frame: once from handleStoppedEvent, once from the
     * Frames panel, once from breakpointReached/positionReached, and once from customizePresentation).
     * With 12 frames, that's 48 calls total per breakpoint hit.
     *
     * Each uncached call involves: pathResolver.remoteToLocal() + LocalFileSystem.findFileByPath()
     * + potentially refreshAndFindFileByPath() + File.exists() + File.canonicalPath.
     * Caching eliminates ~75% of these expensive operations.
     */
    @Volatile
    private var cachedSourcePosition: XSourcePosition? = null
    @Volatile
    private var sourcePositionResolved = false

    override fun getSourcePosition(): XSourcePosition? {
        // Fast path: return cached result (including null, which means "unresolvable")
        if (sourcePositionResolved) return cachedSourcePosition

        val result = resolveSourcePosition()
        cachedSourcePosition = result
        sourcePositionResolved = true
        return result
    }

    private fun resolveSourcePosition(): XSourcePosition? {
        val localPath = pathResolver.remoteToLocal(frameInfo.file) ?: frameInfo.file
        val lfs = LocalFileSystem.getInstance()

        // Try 1: direct findFileByPath (fastest, works when path casing is correct)
        var vFile = lfs.findFileByPath(localPath)

        // Try 2: if direct lookup failed, the VFS cache might be stale — refresh and retry
        if (vFile == null) {
            logger.debug("resolveSourcePosition: findFileByPath failed for '$localPath', trying refreshAndFindFileByPath")
            vFile = lfs.refreshAndFindFileByPath(localPath)
        }

        // Try 3: on Windows, if the path has wrong casing (e.g. all-lowercase from LuaPanda),
        // resolve via File.canonicalPath to get the real on-disk casing
        if (vFile == null) {
            val file = java.io.File(localPath)
            if (file.exists()) {
                val canonicalPath = file.canonicalPath.replace('\\', '/')
                logger.debug("resolveSourcePosition: trying canonical path '$canonicalPath' (original: '$localPath')")
                vFile = lfs.findFileByPath(canonicalPath)
                    ?: lfs.refreshAndFindFileByPath(canonicalPath)
            }
        }

        if (vFile == null) {
            logger.warn("resolveSourcePosition: cannot find VirtualFile for path '$localPath' (remote: '${frameInfo.file}')")
            return null
        }
        // LuaPanda uses 1-based lines, IntelliJ uses 0-based
        val pos = XSourcePositionImpl.create(vFile, frameInfo.line - 1)
        if (logger.isDebugEnabled) {
            logger.debug("resolveSourcePosition: ${frameInfo.file} -> ${vFile.path}, line ${frameInfo.line} -> position=$pos")
        }
        return pos
    }

    override fun customizePresentation(component: ColoredTextContainer) {
        val funcName = frameInfo.functionName.ifEmpty { "<anonymous>" }
        val fileName = frameInfo.file.substringAfterLast('/').substringAfterLast('\\')
        component.append("$funcName (", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append("$fileName:${frameInfo.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.append(")", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.setIcon(LuaPandaIcons.LUA_FILE)
    }

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList()

        // Create groups: Locals, Upvalues, Globals
        // LuaPanda.lua getVariable protocol uses varRef ranges:
        //   10000~19999 = local variables
        //   20000~29999 = global variables
        //   30000+      = upvalues
        // stackId = frameInfo.level (stack frame index, 1-based from LuaPanda.lua)
        children.addTopGroup(VariableGroup("Locals", debugProcess, frameInfo.level, 10000))
        children.addTopGroup(VariableGroup("Upvalues", debugProcess, frameInfo.level, 30000))
        children.addTopGroup(VariableGroup("Globals", debugProcess, frameInfo.level, 20000))

        node.addChildren(children, true)
    }

    override fun getEvaluator(): XDebuggerEvaluator {
        return LuaPandaEvaluator(debugProcess, frameInfo.level)
    }
}

/**
 * A group of variables (locals, upvalues, globals) in the Variables panel.
 *
 * Sends a getVariable request to LuaPanda.lua with the appropriate varRef
 * and stackId for async response matching via callbackId.
 *
 * LuaPanda.lua getVariable protocol:
 *   varRef 10000~19999 = local variables
 *   varRef 20000~29999 = global variables
 *   varRef 30000+      = upvalues
 *   varRef < 10000     = child variable expansion (table members)
 *   stackId = stack frame level
 */
private class VariableGroup(
    private val groupName: String,
    private val debugProcess: LuaPandaDebugProcess,
    private val frameLevel: Int,
    private val varRef: Int
) : XValueGroup(groupName) {

    override fun computeChildren(node: XCompositeNode) {
        // Request variables from LuaPanda.lua with correct varRef and stackId
        debugProcess.sendGetVariablesRequest(varRef, frameLevel) { response ->
            try {
                // Type-safe access: "info" may not always be a JsonArray
                val infoElem = response.get("info")
                val infoArray = if (infoElem != null && infoElem.isJsonArray) infoElem.asJsonArray else null
                if (infoArray != null) {
                    val children = XValueChildrenList()
                    for (element in infoArray) {
                        if (!element.isJsonObject) continue
                        val varObj = element.asJsonObject
                        val name = varObj.get("name")?.asString ?: continue
                        val value = varObj.get("value")?.asString ?: ""
                        val type = varObj.get("type")?.asString ?: ""
                        val childRef = varObj.get("variablesReference")?.asInt ?: 0
                        val hasChildren = childRef > 0

                        children.add(name, LuaPandaVariableValue(
                            debugProcess, name, value, type, hasChildren, childRef
                        ))
                    }
                    node.addChildren(children, true)
                } else {
                    node.addChildren(XValueChildrenList.EMPTY, true)
                }
            } catch (e: Exception) {
                node.addChildren(XValueChildrenList.EMPTY, true)
            }
        }
    }

    override fun isAutoExpand(): Boolean = groupName == "Locals"
}

/**
 * Represents a single variable value in the Variables panel.
 * Supports child expansion for table/userdata types by sending
 * getVariable with the Lua-side variablesReference.
 *
 * Presentation is enhanced with:
 *   - Type-specific icons (number, string, boolean, table, function, nil, etc.)
 *   - String values displayed via XStringValuePresentation (quotes added by renderer)
 *   - Long values truncated with a "View" full-value evaluator link
 *   - Boolean/nil displayed as Lua keyword style
 */
private class LuaPandaVariableValue(
    private val debugProcess: LuaPandaDebugProcess,
    private val varName: String,
    private val varValue: String,
    private val varType: String,
    private val hasChildren: Boolean,
    private val childRef: Int
) : XNamedValue(varName) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val icon = LuaTypeIcons.iconForType(varType)
        val formattedValue = LuaValueFormatter.formatValue(varValue, varType)
        val presentation = LuaValuePresentationFactory.create(varType, formattedValue)

        // For long values, provide a "View" link to see the full content
        if (varValue.length > XValueNode.MAX_VALUE_LENGTH) {
            node.setFullValueEvaluator(object : XFullValueEvaluator(" View Full Value (${varValue.length} chars)") {
                override fun startEvaluation(callback: XFullValueEvaluator.XFullValueEvaluationCallback) {
                    callback.evaluated(varValue)
                }
            })
        }

        node.setPresentation(icon, presentation, hasChildren)
    }

    override fun computeChildren(node: XCompositeNode) {
        if (!hasChildren || childRef <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        // For child expansion, send varRef=<lua-side reference>, stackId=0
        // LuaPanda.lua handles varRef < 10000 as getVariableRef (child lookup)
        debugProcess.sendGetVariablesRequest(childRef, 0) { response ->
            try {
                // Type-safe access: "info" may not always be a JsonArray
                val infoElem = response.get("info")
                val infoArray = if (infoElem != null && infoElem.isJsonArray) infoElem.asJsonArray else null
                if (infoArray != null) {
                    val children = XValueChildrenList()
                    for (element in infoArray) {
                        if (!element.isJsonObject) continue
                        val varObj = element.asJsonObject
                        val name = varObj.get("name")?.asString ?: continue
                        val value = varObj.get("value")?.asString ?: ""
                        val type = varObj.get("type")?.asString ?: ""
                        val ref = varObj.get("variablesReference")?.asInt ?: 0

                        children.add(name, LuaPandaVariableValue(
                            debugProcess, name, value, type, ref > 0, ref
                        ))
                    }
                    node.addChildren(children, true)
                } else {
                    node.addChildren(XValueChildrenList.EMPTY, true)
                }
            } catch (e: Exception) {
                node.addChildren(XValueChildrenList.EMPTY, true)
            }
        }
    }
}

/**
 * Expression evaluator for the Evaluate Expression dialog, debug console,
 * and **hover variable inspection** (tooltip when hovering over a variable in debug mode).
 *
 * Uses the getWatchedVariable command with callbackId for async response matching.
 *
 * CRITICAL: [getExpressionInfoAtOffset] MUST be overridden! The default implementation
 * returns null, which tells IntelliJ "there is no evaluatable expression at this offset",
 * effectively disabling hover-to-inspect entirely.
 */
private class LuaPandaEvaluator(
    private val debugProcess: LuaPandaDebugProcess,
    private val frameLevel: Int
) : XDebuggerEvaluator() {

    private val logger = Logger.getInstance(LuaPandaEvaluator::class.java)

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        debugProcess.sendEvaluateRequest(expression, frameLevel) { evalResult ->
            val hasChildren = evalResult.variablesReference > 0
            callback.evaluated(LuaPandaEvaluatedValue(
                debugProcess, evalResult.value, evalResult.type, hasChildren, evalResult.variablesReference
            ))
        }
    }

    /**
     * Determine the evaluatable expression at the given document offset.
     *
     * This is the entry point for **hover variable inspection**: when the user hovers
     * over text in the editor during a debug pause, IntelliJ calls this method to find
     * out what expression to evaluate. If we return null, no tooltip is shown.
     *
     * We support Lua identifier patterns including:
     *   - Simple variables: `myVar`, `_count`, `x`
     *   - Dotted member access **prefix up to the cursor**:
     *       hover on `health` in `self.health.max` → expression = `self.health`
     *       hover on `max`    in `self.health.max` → expression = `self.health.max`
     *       hover on `self`   in `self.health.max` → expression = `self`
     *
     * The expression always **ends at the identifier under the cursor**. We do NOT
     * extend rightward across dots, otherwise hovering on `001` in `xx.001.002`
     * would always evaluate `xx.001.002` and show the value of `002` — that is
     * the user-reported bug this method is designed to avoid.
     *
     * We intentionally do NOT include `:method()` calls or `[expr]` indexing
     * in hover expressions to avoid side effects.
     */
    override fun getExpressionInfoAtOffset(
        project: Project,
        document: Document,
        offset: Int,
        sideEffectsAllowed: Boolean
    ): ExpressionInfo? {
        val text = document.text
        if (offset < 0 || offset >= text.length) return null

        // The character at offset must be part of a Lua identifier
        val ch = text[offset]
        if (!ch.isLuaIdentifierPart()) return null

        // Find the boundaries of the simple identifier under the cursor
        var idStart = offset
        while (idStart > 0 && text[idStart - 1].isLuaIdentifierPart()) {
            idStart--
        }
        var idEnd = offset
        while (idEnd < text.length && text[idEnd].isLuaIdentifierPart()) {
            idEnd++
        }

        // Expand leftward across dot-separated identifiers: `self.health.max`
        // We walk left from idStart: if we see '.' preceded by an identifier, include it.
        //
        // CRITICAL: We expand LEFT but NOT right. The expression must end at the
        // identifier under the cursor — otherwise hovering on `001` in `xx.001.002`
        // would evaluate the entire `xx.001.002` and show the value of `002`,
        // which is exactly the bug we're fixing. The user expects to see the value
        // of `xx.001` when hovering on `001`.
        var exprStart = idStart
        var pos = idStart
        while (pos > 0) {
            // Check for '.' immediately before the current identifier start
            if (pos - 1 >= 0 && text[pos - 1] == '.') {
                // Find the identifier before the dot
                var prevStart = pos - 1
                if (prevStart > 0 && text[prevStart - 1].isLuaIdentifierPart()) {
                    prevStart--
                    while (prevStart > 0 && text[prevStart - 1].isLuaIdentifierPart()) {
                        prevStart--
                    }
                    exprStart = prevStart
                    pos = prevStart
                } else {
                    break
                }
            } else {
                break
            }
        }

        // The expression's right boundary is the end of the identifier under the cursor.
        // We deliberately do NOT extend rightward — see comment above.
        val exprEnd = idEnd

        val expression = text.substring(exprStart, exprEnd)

        // Skip Lua keywords — they are not evaluatable
        if (expression in LUA_KEYWORDS) return null

        logger.debug("getExpressionInfoAtOffset: offset=$offset, expression='$expression' range=[$exprStart, $exprEnd)")
        return ExpressionInfo(TextRange(exprStart, exprEnd), expression)
    }

    companion object {
        /** Lua reserved keywords that should not trigger hover evaluation */
        private val LUA_KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end",
            "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return",
            "then", "true", "until", "while"
        )

        /** Check if a character can be part of a Lua identifier (letter, digit, or underscore) */
        private fun Char.isLuaIdentifierPart(): Boolean =
            this.isLetterOrDigit() || this == '_'
    }
}

/**
 * XValue for evaluated expressions (hover tooltip / Evaluate Expression dialog).
 *
 * Supports child expansion for table/userdata/function types by using
 * the variablesReference returned from LuaPanda.lua's processWatchedExp.
 *
 * When the evaluated result is a table (e.g. `self`), LuaPanda.lua stores the
 * actual table reference in variableRefTab and returns a variablesReference index.
 * We use sendGetVariablesRequest with this index to retrieve the table's members,
 * enabling the user to expand and inspect children in the hover tooltip or
 * Evaluate Expression panel.
 *
 * Presentation enhanced with type icons, formatted values, and full-value viewer.
 */
private class LuaPandaEvaluatedValue(
    private val debugProcess: LuaPandaDebugProcess,
    private val varValue: String,
    private val varType: String,
    private val hasChildren: Boolean,
    private val childRef: Int
) : XValue() {

    private val logger = Logger.getInstance(LuaPandaEvaluatedValue::class.java)

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        logger.debug("LuaPandaEvaluatedValue.computePresentation: type='$varType', value='${varValue.take(100)}', hasChildren=$hasChildren, childRef=$childRef, place=$place")
        val icon = LuaTypeIcons.iconForType(varType)
        val formattedValue = LuaValueFormatter.formatValue(varValue, varType)
        val presentation = LuaValuePresentationFactory.create(varType, formattedValue)

        if (varValue.length > XValueNode.MAX_VALUE_LENGTH) {
            node.setFullValueEvaluator(object : XFullValueEvaluator(" View Full Value (${varValue.length} chars)") {
                override fun startEvaluation(callback: XFullValueEvaluator.XFullValueEvaluationCallback) {
                    callback.evaluated(varValue)
                }
            })
        }

        node.setPresentation(icon, presentation, hasChildren)
    }

    override fun computeChildren(node: XCompositeNode) {
        logger.debug("LuaPandaEvaluatedValue.computeChildren: hasChildren=$hasChildren, childRef=$childRef")
        if (!hasChildren || childRef <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        // For child expansion, send varRef=<lua-side variablesReference>, stackId=0.
        // LuaPanda.lua's getVariable handler treats varRef < 10000 as a variableRefTab
        // lookup — it retrieves variableRefTab[varRef] and enumerates its members.
        // This is the same mechanism used by the Variables panel for table expansion.
        debugProcess.sendGetVariablesRequest(childRef, 0) { response ->
            try {
                val infoElem = response.get("info")
                val infoArray = if (infoElem != null && infoElem.isJsonArray) infoElem.asJsonArray else null
                if (infoArray != null) {
                    val children = XValueChildrenList()
                    for (element in infoArray) {
                        if (!element.isJsonObject) continue
                        val varObj = element.asJsonObject
                        val name = varObj.get("name")?.asString ?: continue
                        val value = varObj.get("value")?.asString ?: ""
                        val type = varObj.get("type")?.asString ?: ""
                        val ref = varObj.get("variablesReference")?.asInt ?: 0

                        children.add(name, LuaPandaEvaluatedValue(
                            debugProcess, value, type, ref > 0, ref
                        ))
                    }
                    node.addChildren(children, true)
                } else {
                    node.addChildren(XValueChildrenList.EMPTY, true)
                }
            } catch (e: Exception) {
                node.addChildren(XValueChildrenList.EMPTY, true)
            }
        }
    }
}

// ==================== Variable Presentation Helpers ====================

/**
 * Maps Lua types to custom LuaPanda type icons.
 *
 * Each Lua type has a dedicated SVG icon: pastel background + bold dark letter,
 * designed for comfortable long-session readability:
 *   - number   → light blue bg (#D6E4F0) + dark blue "N" (#2A6496)
 *   - string   → light green bg (#D7ECD9) + dark green "S" (#2E7D32)
 *   - boolean  → light orange bg (#FDE8D0) + dark orange "B" (#BF6900)
 *   - table    → light purple bg (#E8D5F5) + dark purple "T" (#7B1FA2)
 *   - function → light red bg (#F5D5D5) + dark red "F" (#C62828)
 *   - nil      → light grey bg (#DEE4E7) + medium grey "∅" (#546E7A)
 *   - userdata → light teal bg (#D0ECE8) + dark teal "U" (#00695C)
 *   - thread   → light yellow bg (#F5ECD0) + dark amber "⇉" (#F57F17)
 */
internal object LuaTypeIcons {

    fun iconForType(luaType: String): Icon? {
        return when (luaType.lowercase()) {
            "number", "int", "float", "integer" -> LuaPandaIcons.TYPE_NUMBER
            "string" -> LuaPandaIcons.TYPE_STRING
            "boolean", "bool" -> LuaPandaIcons.TYPE_BOOLEAN
            "table" -> LuaPandaIcons.TYPE_TABLE
            "function" -> LuaPandaIcons.TYPE_FUNCTION
            "nil" -> LuaPandaIcons.TYPE_NIL
            "userdata", "lightuserdata" -> LuaPandaIcons.TYPE_USERDATA
            "thread" -> LuaPandaIcons.TYPE_THREAD
            else -> null
        }
    }

    fun displayType(luaType: String): String {
        return when (luaType.lowercase()) {
            "number", "int", "float", "integer" -> "number"
            "string" -> "string"
            "boolean", "bool" -> "boolean"
            "table" -> "table"
            "function" -> "function"
            "nil" -> "nil"
            "userdata" -> "userdata"
            "lightuserdata" -> "lightuserdata"
            "thread" -> "thread"
            else -> luaType.ifEmpty { "unknown" }
        }
    }
}

/**
 * Factory for creating type-specific [XValuePresentation] instances.
 *
 * IntelliJ's debugger framework renders variable values using XValuePresentation subclasses.
 * Each subclass can use a different [TextAttributesKey] for syntax-highlighted rendering:
 *   - [XNumericValuePresentation] → number highlighting (blue)
 *   - [XStringValuePresentation]  → string highlighting (green)
 *   - Keyword presentation        → keyword highlighting (orange) for boolean/nil
 *   - Regular presentation         → default text for table/function/userdata/thread
 *
 * The `type` parameter shown in grey beside the value uses [LuaTypeIcons.displayType].
 */
internal object LuaValuePresentationFactory {

    fun create(luaType: String, formattedValue: String): XValuePresentation {
        val displayType = LuaTypeIcons.displayType(luaType)
        return when (luaType.lowercase()) {
            "number", "int", "float", "integer" ->
                XNumericValuePresentation(formattedValue)

            "string" ->
                XStringValuePresentation(formattedValue)

            "boolean", "bool" ->
                LuaKeywordPresentation(formattedValue, displayType)

            "nil" ->
                LuaKeywordPresentation(formattedValue, displayType)

            "table" ->
                LuaTypePresentation(formattedValue, displayType)

            "function" ->
                LuaFunctionPresentation(formattedValue, displayType)

            "userdata", "lightuserdata" ->
                LuaTypePresentation(formattedValue, displayType)

            "thread" ->
                LuaTypePresentation(formattedValue, displayType)

            else ->
                LuaTypePresentation(formattedValue, displayType)
        }
    }
}

/**
 * Presentation for Lua keyword values (boolean true/false, nil).
 * Renders with keyword text attributes for syntax-highlighted display.
 */
private class LuaKeywordPresentation(
    private val value: String,
    private val typeName: String
) : XValuePresentation() {

    override fun getType(): String = typeName

    override fun renderValue(renderer: XValueTextRenderer) {
        renderer.renderKeywordValue(value)
    }
}

/**
 * Presentation for general Lua types (table, userdata, thread).
 * Shows value with type annotation in standard text.
 */
private class LuaTypePresentation(
    private val value: String,
    private val typeName: String
) : XValuePresentation() {

    override fun getType(): String = typeName

    override fun renderValue(renderer: XValueTextRenderer) {
        renderer.renderValue(value)
    }
}

/**
 * Presentation for Lua function values.
 * Renders using keyword style to distinguish from plain values.
 */
private class LuaFunctionPresentation(
    private val value: String,
    private val typeName: String
) : XValuePresentation() {

    override fun getType(): String = typeName

    override fun renderValue(renderer: XValueTextRenderer) {
        // If it's a function address like "function: 0x...", render with special formatting
        if (value.startsWith("function:") || value.startsWith("function ")) {
            renderer.renderComment(value)
        } else {
            renderer.renderValue(value)
        }
    }
}

/**
 * Formats Lua variable values for display in the debugger UI.
 *
 * Enhances readability by:
 *   - Stripping Lua-side outer quotes from strings (renderer adds its own quotes)
 *   - Displaying nil as keyword: `nil`
 *   - Truncating long strings with ellipsis
 *   - Cleaning up table/function tostring output
 *   - Normalizing number display (10.0 → 10 for integers)
 *   - Normalizing boolean display (True → true)
 */
internal object LuaValueFormatter {

    /** Maximum display length before truncation (in characters) */
    private const val MAX_DISPLAY_LENGTH = 512

    fun formatValue(value: String, type: String): String {
        return when (type.lowercase()) {
            "string" -> formatString(value)
            "nil" -> "nil"
            "boolean", "bool" -> value.lowercase()
            "number", "int", "float", "integer" -> formatNumber(value)
            "table" -> formatTable(value)
            "function" -> formatFunction(value)
            "userdata", "lightuserdata" -> truncate(value)
            "thread" -> truncate(value)
            else -> truncate(value)
        }
    }

    /**
     * Format string value for display.
     *
     * IMPORTANT: XStringValuePresentation.renderValue() calls renderer.renderStringValue()
     * which AUTOMATICALLY wraps the value in double quotes for display. Therefore we must
     * NOT add our own quotes here — we only need to return the raw string content.
     *
     * LuaPanda.lua may return string values in different formats:
     *   - Already quoted: `"hello"` → strip outer quotes → `hello`
     *   - Raw value: `hello` → return as-is → `hello`
     *   - Empty quoted: `""` → strip outer quotes → (empty string)
     *
     * The renderer will then display: `"hello"`, `"hello"`, `""` respectively.
     */
    private fun formatString(value: String): String {
        // If Lua side sent the string already wrapped in quotes, strip the outer quotes
        // to avoid double-quoting by XStringValuePresentation
        val raw = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
        return truncate(raw)
    }

    private fun formatNumber(value: String): String {
        val num = value.toDoubleOrNull()
        if (num != null && num == num.toLong().toDouble() && !value.contains("e", ignoreCase = true)) {
            return num.toLong().toString()
        }
        return value
    }

    private fun formatTable(value: String): String {
        if (value.startsWith("table:") || value.startsWith("table ")) {
            return value
        }
        return truncate(value)
    }

    private fun formatFunction(value: String): String {
        if (value.startsWith("function:") || value.startsWith("function ")) {
            return value
        }
        return truncate(value)
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_DISPLAY_LENGTH) return value
        return value.substring(0, MAX_DISPLAY_LENGTH) + "…"
    }
}
