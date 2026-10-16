package com.luapanda.idea.debugger

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * UI editor for LuaPanda run configuration settings.
 *
 * Includes a [UsageTemplatePanel] (inspired by EmmyLua's EmmyDebugSettingsPanel)
 * that dynamically generates a Lua code snippet showing users how to integrate
 * LuaPanda debugger into their Lua project. The template updates in real-time
 * as the user modifies Host, Port, and Connection Mode settings.
 */
class LuaPandaRunConfigurationEditor(
    private val project: Project
) : SettingsEditor<LuaPandaRunConfiguration>() {

    private val hostField = JBTextField("127.0.0.1")
    private val portField = JBTextField("8818")
    private val connectionModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Server (IDE listens)", "Client (IDE connects)")))
    private val autoReconnectCheckbox = JBCheckBox("Auto reconnect", true)
    private val stopOnEntryCheckbox = JBCheckBox("Stop on entry (pause at first line)", false)
    private val luaPandaScriptPathField = JBTextField()
    private val useCHookCheckbox = JBCheckBox("Use C hook library (libpdebug) — faster but may fail to load", true)
    private val clibPathField = JBTextField()
    private val handshakeFallbackMsField = JBTextField("3000")

    // Usage template: delegated to UsageTemplatePanel for better separation of concerns
    private var usageTemplatePanel: UsageTemplatePanel? = null

    override fun createEditor(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        var row = 0

        // Host
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("Host:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(hostField, gbc)

        // Port
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("Port:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(portField, gbc)

        // Connection Mode
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("Connection Mode:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(connectionModeCombo, gbc)

        // Auto Reconnect
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(autoReconnectCheckbox, gbc)
        gbc.gridwidth = 1

        // Stop on Entry
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(stopOnEntryCheckbox, gbc)
        gbc.gridwidth = 1

        // LuaPanda.lua script path
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("LuaPanda.lua path:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(luaPandaScriptPathField, gbc)

        // C Hook Library toggle — default off; users opt-in after confirming Lua runtime compatibility
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(useCHookCheckbox, gbc)
        gbc.gridwidth = 1

        // C Hook Library path (libpdebug) — only used when useCHookCheckbox is selected
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("libpdebug path:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(clibPathField, gbc)

        // Hint for clibPath
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        val clibHint = JBLabel("<html><font color='gray' size='2'>Optional: path to a custom-built libpdebug directory " +
                "(e.g. LuaPanda/Debugger/debugger_lib/). Leave empty to use the plugin-bundled libpdebug " +
                "(Lua 5.1/5.3/5.4, Win x86/x64, Mac x64/arm64). Only used when C hook is enabled above.</font></html>")
        panel.add(clibHint, gbc)
        gbc.gridwidth = 1

        // Sync clibPathField enablement with the useCHook checkbox
        val syncClibEnabled = {
            clibPathField.isEnabled = useCHookCheckbox.isSelected
            clibHint.isEnabled = useCHookCheckbox.isSelected
        }
        syncClibEnabled()
        useCHookCheckbox.addActionListener { syncClibEnabled() }

        // Handshake fallback timeout — advanced tuning knob for remote debugging.
        // See LuaPandaRunConfiguration.handshakeFallbackMs for the full rationale.
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("Handshake fallback (ms):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(handshakeFallbackMsField, gbc)

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        val handshakeHint = JBLabel("<html><font color='gray' size='2'>Safety-net delay before resending breakpoints if LuaPanda.lua's initSuccess " +
                "reply is not received. Default: 3000ms (tuned for remote/LAN debugging). " +
                "Lowering below ~1500ms on non-loopback connections can cause \"Connection reset\" during handshake.</font></html>")
        panel.add(handshakeHint, gbc)
        gbc.gridwidth = 1

        // ==================== Usage Template Section ====================
        // Delegated to UsageTemplatePanel (inspired by EmmyLua's EmmyDebugSettingsPanel)
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(12, 4, 4, 4) // Extra top margin to visually separate
        usageTemplatePanel = UsageTemplatePanel(project, generateTemplateCode())
        panel.add(usageTemplatePanel!!.component, gbc)
        gbc.gridwidth = 1; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(4, 4, 4, 4)

        // Register listeners to update the code template when user changes settings
        registerChangeListeners()

        return panel
    }

    /**
     * Generate the Lua code template based on current UI settings.
     *
     * LuaPanda has two distinct APIs:
     * - `LuaPanda.start(host, port)` — Lua acts as **client**, connects to IDE
     * - `LuaPanda.startServer(host, port)` — Lua acts as **server**, IDE connects to Lua
     *
     * Mapping to our connection modes:
     * - **Server (IDE listens)**: IDE opens a TCP server, Lua connects → `start()`
     * - **Client (IDE connects)**: Lua opens a TCP server, IDE connects → `startServer()`
     *
     * The require path uses a file path style (e.g. "x/LuaPanda") to remind users
     * that LuaPanda.lua must be reachable via package.path.
     */
    private fun generateTemplateCode(): String {
        val host = hostField.text.trim().ifEmpty { "127.0.0.1" }
        val port = portField.text.trim().ifEmpty { "8818" }
        val isServerMode = connectionModeCombo.selectedIndex == 0

        val sb = StringBuilder()
        sb.appendLine("-- Add the following code at the entry point of your Lua program")
        sb.appendLine("-- Change the require path to match your LuaPanda.lua location")

        if (isServerMode) {
            sb.appendLine("-- Server mode: IDE listens on port $port, Lua connects to IDE")
            sb.appendLine("-- Step 1: Click Debug in IDE  →  Step 2: Run your Lua program")
            sb.appendLine("require(\"xx/LuaPanda\").start(\"$host\", $port)")
        } else {
            sb.appendLine("-- Client mode: Lua listens on port $port, IDE connects to Lua")
            sb.appendLine("-- Step 1: Run your Lua program  →  Step 2: Click Debug in IDE")
            sb.appendLine("require(\"xx/LuaPanda\").startServer(\"$host\", $port)")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Update the code template via [UsageTemplatePanel].
     * Called whenever Host, Port, or Connection Mode changes.
     */
    private fun updateCodeTemplate() {
        usageTemplatePanel?.updateTemplate(generateTemplateCode())
    }

    /**
     * Register listeners on Host, Port, Connection Mode fields
     * to trigger real-time code template refresh.
     */
    private fun registerChangeListeners() {
        val textChangeListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateCodeTemplate()
            override fun removeUpdate(e: DocumentEvent?) = updateCodeTemplate()
            override fun changedUpdate(e: DocumentEvent?) = updateCodeTemplate()
        }

        hostField.document.addDocumentListener(textChangeListener)
        portField.document.addDocumentListener(textChangeListener)

        connectionModeCombo.addActionListener {
            updateCodeTemplate()
        }
    }

    override fun resetEditorFrom(config: LuaPandaRunConfiguration) {
        hostField.text = config.host
        portField.text = config.port.toString()
        connectionModeCombo.selectedIndex = if (config.connectionMode == LuaPandaRunConfiguration.ConnectionMode.SERVER) 0 else 1
        autoReconnectCheckbox.isSelected = config.autoReconnect
        stopOnEntryCheckbox.isSelected = config.stopOnEntry
        luaPandaScriptPathField.text = config.luaPandaScriptPath
        useCHookCheckbox.isSelected = config.useCHook
        clibPathField.text = config.clibPath
        clibPathField.isEnabled = config.useCHook
        handshakeFallbackMsField.text = config.handshakeFallbackMs.toString()
        // Refresh template after loading configuration values
        updateCodeTemplate()
    }

    override fun applyEditorTo(config: LuaPandaRunConfiguration) {
        config.host = hostField.text.trim()
        config.port = portField.text.trim().toIntOrNull() ?: 8818
        config.connectionMode = if (connectionModeCombo.selectedIndex == 0)
            LuaPandaRunConfiguration.ConnectionMode.SERVER
        else
            LuaPandaRunConfiguration.ConnectionMode.CLIENT
        config.autoReconnect = autoReconnectCheckbox.isSelected
        config.stopOnEntry = stopOnEntryCheckbox.isSelected
        config.luaPandaScriptPath = luaPandaScriptPathField.text.trim()
        config.useCHook = useCHookCheckbox.isSelected
        config.clibPath = clibPathField.text.trim()
        // Clamp to a sane range: >=100ms (below that even loopback can miss the reply),
        // <=30s (anything higher would make breakpoints feel "stuck" if reply is truly lost).
        config.handshakeFallbackMs = (handshakeFallbackMsField.text.trim().toIntOrNull() ?: 3000)
            .coerceIn(100, 30_000)
    }

    override fun disposeEditor() {
        usageTemplatePanel?.dispose()
        usageTemplatePanel = null
        super.disposeEditor()
    }
}
