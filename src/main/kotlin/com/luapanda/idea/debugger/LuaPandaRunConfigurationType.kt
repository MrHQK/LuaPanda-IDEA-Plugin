package com.luapanda.idea.debugger

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.luapanda.idea.LuaPandaIcons
import javax.swing.Icon

/**
 * Registers the "LuaPanda Debug" run configuration type in the IDE.
 */
class LuaPandaRunConfigurationType : ConfigurationType {

    override fun getDisplayName(): String = "LuaPanda Debug"

    override fun getConfigurationTypeDescription(): String =
        "Debug Lua scripts using LuaPanda debugger"

    override fun getIcon(): Icon = LuaPandaIcons.LUA_DEBUG

    override fun getId(): String = "LuaPandaDebugConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(LuaPandaRunConfigurationFactory(this))
}

class LuaPandaRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return LuaPandaRunConfiguration(project, this, "LuaPanda Debug")
    }

    override fun getId(): String = "LuaPandaDebugConfigurationFactory"

    override fun getName(): String = "LuaPanda Debug"
}
