package com.luapanda.idea.debugger.breakpoints

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.luapanda.idea.LuaPandaIcons
import com.luapanda.idea.debugger.LuaXExpression
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Breakpoint Manager Panel — provides a comprehensive view of all Lua breakpoints in the project.
 *
 * Features:
 *   - List view showing file name, line number, type (normal/condition/log), enabled state
 *   - Double-click to navigate to breakpoint source location
 *   - Checkbox to enable/disable individual breakpoints
 *   - Toolbar with Enable All / Disable All / Remove All / Refresh actions
 *   - Filter by folder and by breakpoint type (Line/Condition/Logpoint)
 *   - Right-click context menu: Navigate, Toggle Enable, Edit Condition, Remove
 *   - Tooltip showing full file path
 *
 * Listens to XBreakpointManager events to stay synchronized with IDE's breakpoint state.
 */
class LuaBreakpointManagerPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val breakpointTableModel = BreakpointTableModel()
    private val breakpointTable = JBTable(breakpointTableModel)

    /** Full list of all Lua breakpoints (unfiltered) */
    private val allBreakpoints = mutableListOf<XLineBreakpoint<*>>()

    /** Filtered list currently displayed in the table */
    private val displayedBreakpoints = mutableListOf<XLineBreakpoint<*>>()

    // ==================== Filter State ====================

    /** All unique folder paths extracted from breakpoints */
    private val folderList = mutableListOf<String>()

    /** Current folder filter selection (null = "All Folders") */
    private var selectedFolder: String? = null

    /** Current type filter selection (null = "All Types") */
    private var selectedType: String? = null

    /** Folder filter combo box */
    private val folderCombo = ComboBox<String>()

    /** Type filter combo box */
    private val typeCombo = ComboBox<String>()

    init {
        setupFilterBar()
        setupTable()
        setupToolbar()
        refreshBreakpoints()
        registerBreakpointListener()
    }

    // ==================== Filter Bar Setup ====================

    private fun setupFilterBar() {
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

        // Folder filter
        filterPanel.add(JLabel("Folder:"))
        folderCombo.apply {
            addItem(ALL_FOLDERS_LABEL)
            addActionListener {
                val sel = selectedItem as? String
                selectedFolder = if (sel == ALL_FOLDERS_LABEL) null else sel
                applyFilters()
            }
            toolTipText = "Filter breakpoints by folder"
        }
        filterPanel.add(folderCombo)

        // Type filter
        filterPanel.add(Box.createHorizontalStrut(12))
        filterPanel.add(JLabel("Type:"))
        typeCombo.apply {
            addItem(ALL_TYPES_LABEL)
            addItem(TYPE_LINE)
            addItem(TYPE_CONDITION)
            addItem(TYPE_LOGPOINT)
            addActionListener {
                val sel = selectedItem as? String
                selectedType = if (sel == ALL_TYPES_LABEL) null else sel
                applyFilters()
            }
            toolTipText = "Filter breakpoints by type"
        }
        filterPanel.add(typeCombo)

        // Breakpoint count label
        filterPanel.add(Box.createHorizontalStrut(12))
        filterPanel.add(countLabel)

        add(filterPanel, BorderLayout.SOUTH)
    }

    private val countLabel = JLabel("").apply {
        foreground = JBColor.GRAY
    }

    private fun updateCountLabel() {
        val total = allBreakpoints.size
        val shown = displayedBreakpoints.size
        countLabel.text = if (total == shown) {
            "$total breakpoint(s)"
        } else {
            "$shown / $total breakpoint(s)"
        }
    }

    // ==================== Table Setup ====================

    private fun setupTable() {
        breakpointTable.apply {
            setShowGrid(false)
            intercellSpacing = java.awt.Dimension(0, 0)
            rowHeight = 24
            tableHeader.reorderingAllowed = false

            // Column widths
            columnModel.getColumn(COL_ENABLED).apply {
                preferredWidth = 30
                maxWidth = 30
                minWidth = 30
            }
            columnModel.getColumn(COL_ICON).apply {
                preferredWidth = 28
                maxWidth = 28
                minWidth = 28
            }
            columnModel.getColumn(COL_FILE).preferredWidth = 200
            columnModel.getColumn(COL_LINE).apply {
                preferredWidth = 50
                maxWidth = 60
            }
            columnModel.getColumn(COL_TYPE).preferredWidth = 80

            // Custom renderers
            columnModel.getColumn(COL_ENABLED).cellRenderer = CheckboxRenderer()
            columnModel.getColumn(COL_ENABLED).cellEditor = DefaultCellEditor(JCheckBox().apply {
                horizontalAlignment = JCheckBox.CENTER
            })
            columnModel.getColumn(COL_ICON).cellRenderer = IconRenderer()
            columnModel.getColumn(COL_FILE).cellRenderer = FileNameRenderer()
            columnModel.getColumn(COL_LINE).cellRenderer = CenteredRenderer()
            columnModel.getColumn(COL_TYPE).cellRenderer = TypeRenderer()

            // Double-click to navigate
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val row = breakpointTable.rowAtPoint(e.point)
                        val col = breakpointTable.columnAtPoint(e.point)
                        if (row >= 0 && col != COL_ENABLED) { // Don't navigate on checkbox double-click
                            navigateToBreakpoint(row)
                        }
                    }
                }
            })

            // Single-click on checkbox column to toggle enabled state
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = breakpointTable.rowAtPoint(e.point)
                    val col = breakpointTable.columnAtPoint(e.point)
                    if (row >= 0 && col == COL_ENABLED && e.clickCount == 1) {
                        toggleBreakpointEnabled(row)
                    }
                }
            })

            // Right-click context menu
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    handleContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleContextMenu(e)
                }

                private fun handleContextMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val row = breakpointTable.rowAtPoint(e.point)
                    if (row < 0 || row >= displayedBreakpoints.size) return
                    // Select the row under the right-click
                    breakpointTable.setRowSelectionInterval(row, row)
                    showContextMenu(e, row)
                }
            })
        }

        val scrollPane = JBScrollPane(breakpointTable)
        add(scrollPane, BorderLayout.CENTER)

        breakpointTable.emptyText.text = "No Lua breakpoints"
    }

    // ==================== Context Menu ====================

    private fun showContextMenu(e: MouseEvent, row: Int) {
        val bp = displayedBreakpoints.getOrNull(row) ?: return
        val popup = JPopupMenu()

        // Navigate to source
        val navigateItem = JMenuItem("Go to Source", AllIcons.Actions.EditSource)
        navigateItem.addActionListener { navigateToBreakpoint(row) }
        popup.add(navigateItem)

        popup.addSeparator()

        // Toggle enable/disable
        val toggleText = if (bp.isEnabled) "Disable Breakpoint" else "Enable Breakpoint"
        val toggleIcon = if (bp.isEnabled) AllIcons.Actions.Suspend else AllIcons.Debugger.MuteBreakpoints
        val toggleItem = JMenuItem(toggleText, toggleIcon)
        toggleItem.addActionListener { toggleBreakpointEnabled(row) }
        popup.add(toggleItem)

        // Edit condition
        val editConditionItem = JMenuItem("Edit Condition...", AllIcons.Actions.Edit)
        editConditionItem.addActionListener { editBreakpointCondition(row) }
        popup.add(editConditionItem)

        // Edit log message
        val editLogItem = JMenuItem("Edit Log Message...", AllIcons.Debugger.Console)
        editLogItem.addActionListener { editBreakpointLogMessage(row) }
        popup.add(editLogItem)

        popup.addSeparator()

        // Remove this breakpoint
        val removeItem = JMenuItem("Remove Breakpoint", AllIcons.Actions.GC)
        removeItem.addActionListener { removeBreakpoint(row) }
        popup.add(removeItem)

        popup.show(breakpointTable, e.x, e.y)
    }

    // ==================== Toolbar Setup ====================

    private fun setupToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            add(EnableAllAction())
            add(DisableAllAction())
            addSeparator()
            add(RemoveAllAction())
            addSeparator()
            add(RefreshAction())
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "LuaPandaBreakpointManager",
            actionGroup,
            true
        )
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
    }

    // ==================== Breakpoint Operations ====================

    /**
     * Refresh the breakpoint list from XBreakpointManager.
     * Collects all line breakpoints on .lua files (from both LuaPanda and EmmyLua types).
     * Rebuilds folder list for the filter combo box.
     */
    fun refreshBreakpoints() {
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        val allBp = bpManager.allBreakpoints

        allBreakpoints.clear()
        for (bp in allBp) {
            if (bp is XLineBreakpoint<*>) {
                val file = bp.sourcePosition?.file
                if (file != null && file.extension?.lowercase() == "lua") {
                    allBreakpoints.add(bp)
                }
            }
        }

        // Sort by file name, then by line number
        allBreakpoints.sortWith(compareBy(
            { it.sourcePosition?.file?.name?.lowercase() ?: "" },
            { it.sourcePosition?.line ?: 0 }
        ))

        rebuildFolderList()
        applyFilters()
    }

    /**
     * Rebuild the folder filter combo box items from current breakpoints.
     */
    private fun rebuildFolderList() {
        val previousSelection = selectedFolder
        folderList.clear()

        // Extract unique parent folder paths from all breakpoints
        val folders = mutableSetOf<String>()
        for (bp in allBreakpoints) {
            val file = bp.sourcePosition?.file ?: continue
            val parent = file.parent?.path ?: continue
            // Show relative to project base path for readability
            val projectBase = project.basePath
            val displayPath = if (projectBase != null && parent.startsWith(projectBase)) {
                parent.removePrefix(projectBase).trimStart('/', '\\').ifEmpty { "/" }
            } else {
                parent
            }
            folders.add(displayPath)
        }

        folderList.addAll(folders.sorted())

        // Rebuild combo box items
        folderCombo.removeAllItems()
        folderCombo.addItem(ALL_FOLDERS_LABEL)
        for (folder in folderList) {
            folderCombo.addItem(folder)
        }

        // Restore previous selection if it still exists
        if (previousSelection != null && folderList.contains(previousSelection)) {
            folderCombo.selectedItem = previousSelection
            selectedFolder = previousSelection
        } else {
            folderCombo.selectedItem = ALL_FOLDERS_LABEL
            selectedFolder = null
        }
    }

    /**
     * Apply current folder and type filters to produce the displayed breakpoints list.
     */
    private fun applyFilters() {
        displayedBreakpoints.clear()

        for (bp in allBreakpoints) {
            // Type filter
            if (selectedType != null) {
                val bpType = getBreakpointTypeString(bp)
                if (bpType != selectedType) continue
            }

            // Folder filter
            if (selectedFolder != null) {
                val file = bp.sourcePosition?.file ?: continue
                val parent = file.parent?.path ?: continue
                val projectBase = project.basePath
                val displayPath = if (projectBase != null && parent.startsWith(projectBase)) {
                    parent.removePrefix(projectBase).trimStart('/', '\\').ifEmpty { "/" }
                } else {
                    parent
                }
                if (displayPath != selectedFolder) continue
            }

            displayedBreakpoints.add(bp)
        }

        breakpointTableModel.fireTableDataChanged()
        updateCountLabel()
    }

    private fun navigateToBreakpoint(row: Int) {
        if (row < 0 || row >= displayedBreakpoints.size) return
        val bp = displayedBreakpoints[row]
        val position = bp.sourcePosition ?: return
        val virtualFile = position.file

        // Use OpenFileDescriptor for reliable navigation with line number
        val descriptor = OpenFileDescriptor(project, virtualFile, position.line, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun toggleBreakpointEnabled(row: Int) {
        if (row < 0 || row >= displayedBreakpoints.size) return
        val bp = displayedBreakpoints[row]
        bp.isEnabled = !bp.isEnabled
        breakpointTableModel.fireTableRowsUpdated(row, row)
    }

    private fun enableAllBreakpoints() {
        for (bp in displayedBreakpoints) {
            if (!bp.isEnabled) {
                bp.isEnabled = true
            }
        }
        breakpointTableModel.fireTableDataChanged()
    }

    private fun disableAllBreakpoints() {
        for (bp in displayedBreakpoints) {
            if (bp.isEnabled) {
                bp.isEnabled = false
            }
        }
        breakpointTableModel.fireTableDataChanged()
    }

    private fun removeAllBreakpoints() {
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        // Copy list to avoid ConcurrentModificationException
        val toRemove = displayedBreakpoints.toList()
        for (bp in toRemove) {
            bpManager.removeBreakpoint(bp)
        }
        // The breakpoint listener will trigger refreshBreakpoints()
    }

    private fun removeBreakpoint(row: Int) {
        if (row < 0 || row >= displayedBreakpoints.size) return
        val bp = displayedBreakpoints[row]
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        bpManager.removeBreakpoint(bp)
        // The breakpoint listener will trigger refreshBreakpoints()
    }

    /**
     * Show a dialog to edit the condition expression of a breakpoint.
     * Setting a non-empty condition converts the breakpoint to a conditional breakpoint.
     * Clearing the condition reverts it to a normal line breakpoint.
     */
    private fun editBreakpointCondition(row: Int) {
        if (row < 0 || row >= displayedBreakpoints.size) return
        val bp = displayedBreakpoints[row]

        val currentCondition = bp.conditionExpression?.expression ?: ""
        val fileName = bp.sourcePosition?.file?.name ?: "unknown"
        val line = (bp.sourcePosition?.line ?: 0) + 1

        val newCondition = Messages.showInputDialog(
            project,
            "Condition expression for $fileName:$line\n(Leave empty to remove condition)",
            "Edit Breakpoint Condition",
            null,
            currentCondition,
            null
        )

        // null means dialog was cancelled
        if (newCondition == null) return

        if (newCondition.isBlank()) {
            bp.conditionExpression = null
        } else {
            bp.conditionExpression = LuaXExpression(newCondition)
        }
        breakpointTableModel.fireTableRowsUpdated(row, row)
    }

    /**
     * Show a dialog to edit the log message of a breakpoint.
     * Setting a non-empty log message converts the breakpoint to a logpoint.
     * Clearing the log message reverts it to a normal line breakpoint.
     */
    private fun editBreakpointLogMessage(row: Int) {
        if (row < 0 || row >= displayedBreakpoints.size) return
        val bp = displayedBreakpoints[row]

        val currentLog = bp.logExpressionObject?.expression ?: ""
        val fileName = bp.sourcePosition?.file?.name ?: "unknown"
        val line = (bp.sourcePosition?.line ?: 0) + 1

        val newLog = Messages.showInputDialog(
            project,
            "Log message for $fileName:$line\n(Leave empty to remove log message)\nUse {expression} for evaluated values.",
            "Edit Breakpoint Log Message",
            null,
            currentLog,
            null
        )

        // null means dialog was cancelled
        if (newLog == null) return

        if (newLog.isBlank()) {
            bp.logExpressionObject = null
        } else {
            bp.logExpressionObject = LuaXExpression(newLog)
        }
        breakpointTableModel.fireTableRowsUpdated(row, row)
    }

    // ==================== Breakpoint Listener ====================

    private fun registerBreakpointListener() {
        project.messageBus.connect().subscribe(
            XBreakpointListener.TOPIC,
            object : XBreakpointListener<XBreakpoint<*>> {
                override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
                    SwingUtilities.invokeLater { refreshBreakpoints() }
                }

                override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
                    SwingUtilities.invokeLater { refreshBreakpoints() }
                }

                override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
                    SwingUtilities.invokeLater { refreshBreakpoints() }
                }
            }
        )
    }

    // ==================== Table Model ====================

    private inner class BreakpointTableModel : AbstractTableModel() {

        override fun getRowCount(): Int = displayedBreakpoints.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = when (column) {
            COL_ENABLED -> ""
            COL_ICON -> ""
            COL_FILE -> "File"
            COL_LINE -> "Line"
            COL_TYPE -> "Type"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ENABLED -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex == COL_ENABLED
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            if (rowIndex >= displayedBreakpoints.size) return null
            val bp = displayedBreakpoints[rowIndex]
            return when (columnIndex) {
                COL_ENABLED -> bp.isEnabled
                COL_ICON -> getBreakpointTypeString(bp)
                COL_FILE -> {
                    val file = bp.sourcePosition?.file
                    file?.name ?: "unknown"
                }
                COL_LINE -> (bp.sourcePosition?.line ?: 0) + 1 // Display as 1-based
                COL_TYPE -> getBreakpointTypeString(bp)
                else -> null
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == COL_ENABLED && rowIndex < displayedBreakpoints.size) {
                val enabled = aValue as? Boolean ?: return
                displayedBreakpoints[rowIndex].isEnabled = enabled
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }
    }

    // ==================== Cell Renderers ====================

    private class CheckboxRenderer : DefaultTableCellRenderer() {
        private val checkbox = JCheckBox().apply {
            horizontalAlignment = JCheckBox.CENTER
            isOpaque = true
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            checkbox.isSelected = value as? Boolean ?: false
            checkbox.background = if (isSelected) table.selectionBackground else table.background
            return checkbox
        }
    }

    private inner class IconRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column) as JLabel
            label.horizontalAlignment = JLabel.CENTER
            val typeStr = value as? String ?: ""

            // Use different icons for different breakpoint types
            val bp = displayedBreakpoints.getOrNull(row)
            val isEnabled = bp?.isEnabled ?: true

            label.icon = when {
                typeStr == "Condition" && isEnabled -> AllIcons.Debugger.Db_set_breakpoint
                typeStr == "Condition" && !isEnabled -> AllIcons.Debugger.Db_disabled_breakpoint
                typeStr == "Logpoint" && isEnabled -> AllIcons.Debugger.Db_set_breakpoint
                typeStr == "Logpoint" && !isEnabled -> AllIcons.Debugger.Db_disabled_breakpoint
                isEnabled -> AllIcons.Debugger.Db_set_breakpoint
                else -> AllIcons.Debugger.Db_disabled_breakpoint
            }
            label.text = ""
            return label
        }
    }

    /**
     * Renderer for file name column — shows file name with lua icon,
     * tooltip displays the full file path for easy identification.
     */
    private inner class FileNameRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            label.icon = LuaPandaIcons.LUA_FILE

            // Show full path in tooltip for easy identification of same-named files
            val bp = displayedBreakpoints.getOrNull(row)
            val fullPath = bp?.sourcePosition?.file?.path
            label.toolTipText = fullPath ?: (value as? String)
            return label
        }
    }

    private class CenteredRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = JLabel.CENTER
        }
    }

    private class TypeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            val typeStr = value as? String ?: ""
            label.foreground = when (typeStr) {
                "Condition" -> JBColor(0xBF6900, 0xFFBB5C)
                "Logpoint" -> JBColor(0x2E7D32, 0x7EC87E)
                else -> if (isSelected) table.selectionForeground else table.foreground
            }
            return label
        }
    }

    // ==================== Toolbar Actions ====================

    private inner class EnableAllAction : AnAction(
        "Enable All Breakpoints",
        "Enable all displayed Lua breakpoints",
        AllIcons.Debugger.MuteBreakpoints
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            enableAllBreakpoints()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = displayedBreakpoints.any { !it.isEnabled }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class DisableAllAction : AnAction(
        "Disable All Breakpoints",
        "Disable all displayed Lua breakpoints",
        AllIcons.Actions.Suspend
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            disableAllBreakpoints()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = displayedBreakpoints.any { it.isEnabled }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RemoveAllAction : AnAction(
        "Remove All Breakpoints",
        "Remove all displayed Lua breakpoints",
        AllIcons.Actions.GC
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val count = displayedBreakpoints.size
            val filterInfo = if (allBreakpoints.size != count) " (filtered: $count of ${allBreakpoints.size})" else ""
            val result = Messages.showYesNoDialog(
                project,
                "Remove $count Lua breakpoint(s)$filterInfo?",
                "Remove Breakpoints",
                AllIcons.General.QuestionDialog
            )
            if (result == Messages.YES) {
                removeAllBreakpoints()
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = displayedBreakpoints.isNotEmpty()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RefreshAction : AnAction(
        "Refresh",
        "Refresh breakpoint list",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            refreshBreakpoints()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    // ==================== Utility ====================

    companion object {
        private const val COL_ENABLED = 0
        private const val COL_ICON = 1
        private const val COL_FILE = 2
        private const val COL_LINE = 3
        private const val COL_TYPE = 4

        private const val ALL_FOLDERS_LABEL = "All Folders"
        private const val ALL_TYPES_LABEL = "All Types"
        private const val TYPE_LINE = "Line"
        private const val TYPE_CONDITION = "Condition"
        private const val TYPE_LOGPOINT = "Logpoint"

        fun getBreakpointTypeString(bp: XLineBreakpoint<*>): String {
            val condition = bp.conditionExpression?.expression
            val logMessage = bp.logExpressionObject?.expression
            return when {
                !condition.isNullOrBlank() -> TYPE_CONDITION
                !logMessage.isNullOrBlank() -> TYPE_LOGPOINT
                else -> TYPE_LINE
            }
        }
    }
}
