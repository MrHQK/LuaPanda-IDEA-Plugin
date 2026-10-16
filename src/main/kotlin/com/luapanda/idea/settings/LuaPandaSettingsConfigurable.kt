package com.luapanda.idea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page for LuaPanda plugin (Settings → Tools → LuaPanda).
 */
class LuaPandaSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val luaFmtPathField = TextFieldWithBrowseButton()
    private val luacheckPathField = TextFieldWithBrowseButton()
    private val defaultPortField = JBTextField()
    private val defaultHostField = JBTextField()

    override fun getDisplayName(): String = "LuaPanda"

    override fun createComponent(): JComponent {
        luaFmtPathField.addBrowseFolderListener(
            "Select lua-fmt Executable",
            "Select the path to lua-fmt executable",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        luacheckPathField.addBrowseFolderListener(
            "Select luacheck Executable",
            "Select the path to luacheck executable",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        var row = 0

        // lua-fmt path
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        p.add(JBLabel("lua-fmt path:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(luaFmtPathField, gbc)

        // luacheck path
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        p.add(JBLabel("luacheck path:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(luacheckPathField, gbc)

        // Default host
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        p.add(JBLabel("Default host:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(defaultHostField, gbc)

        // Default port
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        p.add(JBLabel("Default port:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(defaultPortField, gbc)

        // Filler
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        p.add(JPanel(), gbc)

        panel = p
        return p
    }

    override fun isModified(): Boolean {
        val settings = LuaPandaSettings.getInstance()
        return luaFmtPathField.text != settings.luaFmtPath ||
                luacheckPathField.text != settings.luacheckPath ||
                defaultPortField.text != settings.defaultPort.toString() ||
                defaultHostField.text != settings.defaultHost
    }

    override fun apply() {
        val settings = LuaPandaSettings.getInstance()
        settings.luaFmtPath = luaFmtPathField.text
        settings.luacheckPath = luacheckPathField.text
        settings.defaultPort = defaultPortField.text.toIntOrNull() ?: 8818
        settings.defaultHost = defaultHostField.text.ifBlank { "127.0.0.1" }
    }

    override fun reset() {
        val settings = LuaPandaSettings.getInstance()
        luaFmtPathField.text = settings.luaFmtPath
        luacheckPathField.text = settings.luacheckPath
        defaultPortField.text = settings.defaultPort.toString()
        defaultHostField.text = settings.defaultHost
    }

    override fun disposeUIResources() {
        panel = null
    }
}
