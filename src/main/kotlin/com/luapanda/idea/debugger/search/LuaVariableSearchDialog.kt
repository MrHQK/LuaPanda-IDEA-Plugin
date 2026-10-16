package com.luapanda.idea.debugger.search

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.luapanda.idea.debugger.LuaPandaDebugProcess
import com.luapanda.idea.debugger.LuaPandaStackFrame
import com.luapanda.idea.debugger.LuaTypeIcons
import com.luapanda.idea.debugger.LuaValueFormatter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * Non-modal dialog to search variables of the current stack frame by name while paused.
 *
 * Variables (Locals / Upvalues / optionally Globals) are fetched once per pause/frame,
 * table members are expanded recursively up to a configurable depth, and the list is
 * filtered locally as the user types. Matches can be added to Watches or copied.
 */
class LuaVariableSearchDialog(
    private val session: XDebugSession,
    private val debugProcess: LuaPandaDebugProcess
) : DialogWrapper(session.project, false, IdeModalityType.MODELESS) {

    private val props = PropertiesComponent.getInstance()

    private val searchField = SearchTextField(false)
    private val localsBox = JBCheckBox("Locals", props.getBoolean(KEY_LOCALS, true))
    private val upvaluesBox = JBCheckBox("Upvalues", props.getBoolean(KEY_UPVALUES, true))
    private val globalsBox = JBCheckBox("Globals", props.getBoolean(KEY_GLOBALS, false))
    private val depthSpinner = JSpinner(SpinnerNumberModel(props.getInt(KEY_DEPTH, 2).coerceIn(0, MAX_DEPTH), 0, MAX_DEPTH, 1))
    private val caseBox = JBCheckBox("Match case", props.getBoolean(KEY_CASE, false))
    private val regexBox = JBCheckBox("Regex", props.getBoolean(KEY_REGEX, false))
    private val statusLabel = JBLabel()

    private val tableModel = EntryTableModel()
    private val table = JBTable(tableModel)

    /** All collected entries of the current frame (EDT only). */
    private val allEntries = ArrayList<LuaVariableEntry>()
    private var matcher: EntryMatcher = EntryMatcher.create("", false, false)

    private var loadedFrame: LuaPandaStackFrame? = null
    private var collector: LuaVariableCollector? = null
    private var generation = 0
    private var loading = false

    /** Set when a reload is requested while a collection is still running. */
    private var reloadPending = false

    private val addWatchAction = object : AbstractAction("Add to Watches") {
        override fun actionPerformed(e: ActionEvent?) = addSelectedToWatches()
    }

    init {
        title = "Search Lua Variables"
        setCancelButtonText("Close")
        addWatchAction.isEnabled = false
        init()
        // Enter is handled by the search field (jump to results) and the results table
        // (add to watches) — never let it trigger the default button.
        rootPane.defaultButton = null
        installListeners()
        reload(force = true)
    }

    override fun getDimensionServiceKey(): String = "LuaPanda.VariableSearchDialog"

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun createActions(): Array<Action> = arrayOf(addWatchAction, cancelAction)

    override fun createCenterPanel(): JComponent {
        depthSpinner.toolTipText = "Depth of table members to expand (0 = top-level variables only)"
        searchField.textEditor.emptyText.text = "Variable name (use '.' to match the full path, e.g. self.hp)"
        (depthSpinner.editor as? JSpinner.DefaultEditor)?.textField?.columns = 2

        val optionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(localsBox); add(upvaluesBox); add(globalsBox)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(JBUI.scale(2), JBUI.scale(18)) })
            add(JBLabel("Depth:")); add(depthSpinner)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(JBUI.scale(2), JBUI.scale(18)) })
            add(caseBox); add(regexBox)
            add(JButton("Reload", AllIcons.Actions.Refresh).apply { addActionListener { reload(force = true) } })
        }

        val north = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(searchField, BorderLayout.NORTH)
            add(optionsRow, BorderLayout.CENTER)
        }

        setupTable()

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = JBUI.size(820, 480)
            add(north, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    private fun setupTable() {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.emptyText.text = "No matching variables"
        table.setDefaultRenderer(Any::class.java, EntryRenderer())
        table.columnModel.getColumn(COL_PATH).preferredWidth = JBUI.scale(300)
        table.columnModel.getColumn(COL_SCOPE).preferredWidth = JBUI.scale(70)
        table.columnModel.getColumn(COL_TYPE).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(COL_VALUE).preferredWidth = JBUI.scale(360)

        val sorter = TableRowSorter(tableModel)
        sorter.setComparator(COL_PATH, compareBy<LuaVariableEntry>({ it.path.lowercase() }))
        sorter.setComparator(COL_SCOPE, compareBy<LuaVariableEntry>({ it.scope.ordinal }))
        sorter.setComparator(COL_TYPE, compareBy<LuaVariableEntry>({ it.type }))
        sorter.setComparator(COL_VALUE, compareBy<LuaVariableEntry>({ it.value }))
        sorter.sortsOnUpdates = false
        table.rowSorter = sorter

        table.selectionModel.addListSelectionListener {
            addWatchAction.isEnabled = table.selectedRowCount > 0
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                addSelectedToWatches()
                return true
            }
        }.installOn(table)

        val popupGroup = DefaultActionGroup().apply {
            add(tableAction("Add to Watches", AllIcons.Debugger.Watch) { addSelectedToWatches() })
            addSeparator()
            add(tableAction("Copy Path", AllIcons.Actions.Copy) { copySelected { it.path } })
            add(tableAction("Copy Name", null) { copySelected { it.name } })
            add(tableAction("Copy Value", null) { copySelected { it.value } })
        }
        PopupHandler.installPopupMenu(table, popupGroup, "LuaPandaVariableSearchPopup")
    }

    private fun tableAction(text: String, icon: Icon?, run: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun installListeners() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if ((e.keyCode == KeyEvent.VK_DOWN || e.keyCode == KeyEvent.VK_ENTER) && table.rowCount > 0) {
                    table.requestFocusInWindow()
                    if (table.selectedRow < 0) table.setRowSelectionInterval(0, 0)
                    e.consume()
                }
            }
        })
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && table.selectedRowCount > 0) {
                    addSelectedToWatches()
                    e.consume()
                }
            }
        })

        caseBox.addActionListener { saveOptions(); applyFilter() }
        regexBox.addActionListener { saveOptions(); applyFilter() }
        listOf(localsBox, upvaluesBox, globalsBox).forEach { box ->
            box.addActionListener { saveOptions(); reload(force = true) }
        }
        depthSpinner.addChangeListener { saveOptions(); reload(force = true) }

        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = onEdt { reload(force = true) }
            override fun stackFrameChanged() = onEdt { reload(force = false) }
            override fun sessionResumed() = onEdt { invalidate("Program is running. Results will refresh on next pause.") }
            override fun sessionStopped() = onEdt { close(CANCEL_EXIT_CODE) }
        }, disposable)
    }

    private fun saveOptions() {
        props.setValue(KEY_LOCALS, localsBox.isSelected, true)
        props.setValue(KEY_UPVALUES, upvaluesBox.isSelected, true)
        props.setValue(KEY_GLOBALS, globalsBox.isSelected, false)
        props.setValue(KEY_DEPTH, depthSpinner.value as Int, 2)
        props.setValue(KEY_CASE, caseBox.isSelected, false)
        props.setValue(KEY_REGEX, regexBox.isSelected, false)
    }

    // ==================== Loading ====================

    private fun invalidate(message: String) {
        generation++
        collector?.cancel()
        collector = null
        loading = false
        reloadPending = false
        loadedFrame = null
        allEntries.clear()
        tableModel.setEntries(emptyList())
        statusLabel.text = message
    }

    private fun reload(force: Boolean) {
        val frame = session.currentStackFrame as? LuaPandaStackFrame
        if (!force && frame != null && frame === loadedFrame) return
        // LuaPanda.lua answers requests strictly in order while paused; overlapping collections
        // would interleave requests on the wire. Wait for the running one instead.
        if (loading) {
            reloadPending = true
            return
        }

        if (!session.isSuspended || frame == null) {
            invalidate(if (!session.isSuspended) "Program is running. Pause at a breakpoint to search variables." else "No Lua stack frame selected.")
            return
        }
        invalidate("")

        val scopes = buildList {
            if (localsBox.isSelected) add(LuaVariableScope.LOCAL)
            if (upvaluesBox.isSelected) add(LuaVariableScope.UPVALUE)
            if (globalsBox.isSelected) add(LuaVariableScope.GLOBAL)
        }
        loadedFrame = frame
        if (scopes.isEmpty()) {
            statusLabel.text = "Select at least one scope."
            return
        }

        val gen = generation
        val newCollector = LuaVariableCollector(debugProcess, frame.frameLevel, scopes, depthSpinner.value as Int)
        collector = newCollector
        loading = true
        updateStatus()

        ApplicationManager.getApplication().executeOnPooledThread {
            newCollector.collect { batch ->
                onEdt {
                    if (gen != generation) return@onEdt
                    allEntries.addAll(batch)
                    tableModel.appendEntries(batch.filter { matcher.matches(it) })
                    updateStatus()
                }
            }
            onEdt {
                if (gen != generation) return@onEdt
                loading = false
                updateStatus()
                if (reloadPending) {
                    reloadPending = false
                    reload(force = true)
                }
            }
        }
    }

    // ==================== Filtering ====================

    private fun applyFilter() {
        matcher = EntryMatcher.create(searchField.text.trim(), caseBox.isSelected, regexBox.isSelected)
        tableModel.setEntries(allEntries.filter { matcher.matches(it) })
        updateStatus()
    }

    private fun updateStatus() {
        val frameText = loadedFrame?.displayName ?: "-"
        val c = collector
        val sb = StringBuilder("Frame: $frameText  |  ")
        if (matcher.error != null) {
            sb.append("Invalid regex: ${matcher.error}  |  ")
        }
        sb.append("${tableModel.rowCount} match(es) / ${allEntries.size} variable(s)")
        if (loading) sb.append("  |  Loading...")
        if (c != null) {
            c.errorMessage?.let { sb.append("  |  Error: $it") }
            if (c.truncated) {
                sb.append("  |  Request limit (${LuaVariableCollector.DEFAULT_MAX_REQUESTS}) reached, reduce depth for complete results")
            }
        }
        statusLabel.text = sb.toString()
        statusLabel.foreground = if (matcher.error != null || c?.errorMessage != null) {
            JBUI.CurrentTheme.Focus.errorColor(true)
        } else {
            JBUI.CurrentTheme.Label.disabledForeground()
        }
    }

    // ==================== Actions ====================

    private fun selectedEntries(): List<LuaVariableEntry> =
        table.selectedRows.map { tableModel.entryAt(table.convertRowIndexToModel(it)) }

    private fun copySelected(selector: (LuaVariableEntry) -> String) {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(entries.joinToString("\n", transform = selector)))
    }

    private fun addSelectedToWatches() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val watchesView = (session as? XDebugSessionImpl)?.sessionTab?.watchesView
        if (watchesView == null) {
            copySelected { it.path }
            statusLabel.text = "Watches view is not available; expression(s) copied to clipboard."
            return
        }
        entries.forEachIndexed { i, entry ->
            watchesView.addWatchExpression(XExpressionImpl.fromText(entry.path), -1, i == entries.lastIndex)
        }
    }

    override fun dispose() {
        generation++
        collector?.cancel()
        super.dispose()
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!isDisposed) block()
        }, ModalityState.any())
    }

    // ==================== Table ====================

    private class EntryTableModel : AbstractTableModel() {
        private val entries = ArrayList<LuaVariableEntry>()

        fun entryAt(row: Int): LuaVariableEntry = entries[row]

        fun setEntries(newEntries: List<LuaVariableEntry>) {
            entries.clear()
            entries.addAll(newEntries)
            fireTableDataChanged()
        }

        fun appendEntries(newEntries: List<LuaVariableEntry>) {
            if (newEntries.isEmpty()) return
            val start = entries.size
            entries.addAll(newEntries)
            fireTableRowsInserted(start, entries.size - 1)
        }

        override fun getRowCount(): Int = entries.size
        override fun getColumnCount(): Int = COLUMN_NAMES.size
        override fun getColumnName(column: Int): String = COLUMN_NAMES[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = Any::class.java
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = entries[rowIndex]
    }

    private inner class EntryRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ) {
            val entry = value as? LuaVariableEntry ?: return
            when (column) {
                COL_PATH -> appendHighlighted(entry.path, matcher.highlightRanges(entry))
                COL_SCOPE -> append(entry.scope.displayName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                COL_TYPE -> {
                    icon = LuaTypeIcons.iconForType(entry.type)
                    append(LuaTypeIcons.displayType(entry.type), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                COL_VALUE -> {
                    val formatted = LuaValueFormatter.formatValue(entry.value, entry.type)
                    val text = if (entry.type.equals("string", true)) "\"$formatted\"" else formatted
                    append(text.replace('\n', ' ').replace('\r', ' '), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        private fun appendHighlighted(text: String, ranges: List<IntRange>) {
            var pos = 0
            for (r in ranges) {
                if (r.first < pos || r.last >= text.length) continue
                if (r.first > pos) append(text.substring(pos, r.first), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(text.substring(r.first, r.last + 1), HIGHLIGHT_ATTRIBUTES)
                pos = r.last + 1
            }
            if (pos < text.length) append(text.substring(pos), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    companion object {
        private const val COL_PATH = 0
        private const val COL_SCOPE = 1
        private const val COL_TYPE = 2
        private const val COL_VALUE = 3
        private val COLUMN_NAMES = arrayOf("Name / Path", "Scope", "Type", "Value")
        private const val MAX_DEPTH = 6

        private val HIGHLIGHT_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SEARCH_MATCH, null
        )

        private const val KEY_LOCALS = "LuaPanda.VarSearch.locals"
        private const val KEY_UPVALUES = "LuaPanda.VarSearch.upvalues"
        private const val KEY_GLOBALS = "LuaPanda.VarSearch.globals"
        private const val KEY_DEPTH = "LuaPanda.VarSearch.depth"
        private const val KEY_CASE = "LuaPanda.VarSearch.matchCase"
        private const val KEY_REGEX = "LuaPanda.VarSearch.regex"

        /** One dialog per debug session; re-opening focuses the existing one. */
        private val openDialogs = HashMap<XDebugSession, LuaVariableSearchDialog>()

        fun showFor(session: XDebugSession, debugProcess: LuaPandaDebugProcess) {
            openDialogs[session]?.let { existing ->
                if (!existing.isDisposed) {
                    existing.toFront()
                    existing.searchField.requestFocusInWindow()
                    return
                }
            }
            val dialog = LuaVariableSearchDialog(session, debugProcess)
            openDialogs[session] = dialog
            com.intellij.openapi.util.Disposer.register(dialog.disposable) { openDialogs.remove(session) }
            dialog.show()
        }
    }
}

/**
 * Name/path matcher. Matches against the variable name by default; if the query contains
 * `.`, `[` or `:` it is matched against the full access path instead (e.g. `self.hp`).
 */
internal class EntryMatcher private constructor(
    private val query: String,
    private val caseSensitive: Boolean,
    private val regex: Regex?,
    val error: String?
) {
    private val matchPath = query.contains('.') || query.contains('[') || query.contains(':')

    fun matches(entry: LuaVariableEntry): Boolean {
        if (query.isEmpty()) return true
        if (error != null) return false
        val target = if (matchPath) entry.path else entry.name
        return if (regex != null) regex.containsMatchIn(target) else target.contains(query, ignoreCase = !caseSensitive)
    }

    /** Ranges to highlight inside [LuaVariableEntry.path]. */
    fun highlightRanges(entry: LuaVariableEntry): List<IntRange> {
        if (query.isEmpty() || error != null) return emptyList()
        val (text, offset) = if (matchPath) {
            entry.path to 0
        } else {
            val idx = entry.path.lastIndexOf(entry.name)
            if (idx < 0) return emptyList()
            entry.name to idx
        }
        val ranges = ArrayList<IntRange>()
        if (regex != null) {
            regex.findAll(text).forEach { m ->
                if (!m.range.isEmpty()) ranges.add((m.range.first + offset)..(m.range.last + offset))
            }
        } else {
            var from = 0
            while (true) {
                val i = text.indexOf(query, from, ignoreCase = !caseSensitive)
                if (i < 0) break
                ranges.add((i + offset) until (i + offset + query.length))
                from = i + query.length
            }
        }
        return ranges
    }

    companion object {
        fun create(query: String, caseSensitive: Boolean, useRegex: Boolean): EntryMatcher {
            if (!useRegex || query.isEmpty()) return EntryMatcher(query, caseSensitive, null, null)
            return try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                EntryMatcher(query, caseSensitive, Regex(query, options), null)
            } catch (e: Exception) {
                EntryMatcher(query, caseSensitive, null, e.message?.lineSequence()?.firstOrNull() ?: "invalid")
            }
        }
    }
}
