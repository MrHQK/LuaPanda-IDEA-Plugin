package com.luapanda.idea.debugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A reusable panel that displays a read-only Lua code snippet as a "Usage Template".
 *
 * Inspired by EmmyLua's EmmyDebugSettingsPanel pattern: an IntelliJ [EditorEx] instance
 * configured as a read-only viewer with syntax highlighting. The template code updates
 * dynamically via [updateTemplate] whenever the caller's configuration changes.
 *
 * ## Design Pattern (from EmmyLua)
 *
 * EmmyLua uses `EditorFactory.createEditor(document, project, fileType, isViewer=true)`
 * to embed a compact code editor inside a Run Configuration settings panel. The editor
 * shows a code snippet that the user can copy-paste into their project. The snippet
 * refreshes in real-time as the user modifies connection parameters (host, port, mode).
 *
 * ## Usage
 *
 * ```kotlin
 * val templatePanel = UsageTemplatePanel(project)
 * // Add templatePanel.component to your layout
 * // Later, when settings change:
 * templatePanel.updateTemplate("require(\"LuaPanda\").start(\"127.0.0.1\", 8818)")
 * // When done:
 * templatePanel.dispose()
 * ```
 *
 * @param project The current IntelliJ project, used for editor creation.
 * @param initialCode Optional initial code to display. Defaults to empty string.
 * @param editorHeight Preferred height of the editor component in pixels. Defaults to 140.
 */
class UsageTemplatePanel(
    private val project: Project,
    initialCode: String = "",
    editorHeight: Int = 140
) {
    private var document: Document? = null
    private var editor: EditorEx? = null

    /**
     * The Swing component to embed in a parent layout.
     * Contains a titled border panel wrapping the read-only code editor.
     */
    val component: JComponent

    /**
     * The label displayed above the editor.
     * Callers can customize this if needed.
     */
    val headerLabel = JBLabel(
        "<html><b>Usage Template</b> — Copy the following code to your Lua entry script:</html>"
    )

    /**
     * The hint label displayed below the editor.
     * Provides tips about LuaPanda integration.
     */
    val hintLabel = JBLabel(
        "<html><font color='gray' size='2'>" +
                "Tip: Place <code>require(\"LuaPanda\").start()</code> at the very beginning of your Lua entry point, " +
                "and make sure <code>LuaPanda.lua</code> is in your Lua <code>package.path</code>. " +
                "The plugin already bundles the full <code>LuaPanda.lua</code> — get it with " +
                "<b>Tools → Copy LuaPanda.lua to Project</b>." +
                "</font></html>"
    )

    init {
        val editorFactory = EditorFactory.getInstance()
        document = editorFactory.createDocument(initialCode)

        // Try to use Lua file type for syntax highlighting;
        // falls back to plain text if no Lua support is installed.
        val luaFileType = FileTypeManager.getInstance().getFileTypeByExtension("lua")

        editor = editorFactory.createEditor(
            document!!,
            project,
            luaFileType,
            true  // isViewer = true (read-only)
        ) as EditorEx

        // Configure editor appearance for a compact, read-only code snippet
        editor!!.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isAdditionalPageAtBottom = false
            isCaretRowShown = false
            isRightMarginShown = false
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }

        // Set preferred height for the editor
        editor!!.component.preferredSize = Dimension(0, editorHeight)

        // Build the bordered panel
        val editorWrapper = JPanel(BorderLayout())
        editorWrapper.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Lua Code"),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)
        )
        editorWrapper.add(editor!!.component, BorderLayout.CENTER)

        // Assemble the full component: header + editor + hint
        val fullPanel = JPanel(BorderLayout(0, 4))
        fullPanel.add(headerLabel, BorderLayout.NORTH)
        fullPanel.add(editorWrapper, BorderLayout.CENTER)
        fullPanel.add(hintLabel, BorderLayout.SOUTH)

        component = fullPanel
    }

    /**
     * Update the displayed code template.
     * Only performs a write action if the content has actually changed.
     *
     * @param code The new Lua code snippet to display.
     */
    fun updateTemplate(code: String) {
        val doc = document ?: return
        if (doc.text != code) {
            ApplicationManager.getApplication().runWriteAction {
                doc.setText(code)
            }
        }
    }

    /**
     * Release the editor resources.
     * Must be called when the parent component is disposed to avoid memory leaks.
     */
    fun dispose() {
        editor?.let { e ->
            EditorFactory.getInstance().releaseEditor(e)
        }
        editor = null
        document = null
    }
}
