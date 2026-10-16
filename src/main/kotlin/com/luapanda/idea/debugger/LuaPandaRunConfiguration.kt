package com.luapanda.idea.debugger

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * Run configuration that stores LuaPanda debugging parameters.
 *
 * This is a debug-only configuration: only the Debug executor is supported.
 * The Run button is greyed out by:
 *   1. Implementing RunConfigurationWithSuppressedDefaultRunAction to prevent IntelliJ's built-in
 *      DefaultRunProgramRunner from claiming it can run this configuration.
 *   2. getState() returning null for non-debug executors as a safety net.
 *   3. LuaPandaProgramRunner.canRun() only accepting DefaultDebugExecutor.
 */
class LuaPandaRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<RunConfigurationOptions>(project, factory, name),
    RunConfigurationWithSuppressedDefaultRunAction {

    var host: String = "127.0.0.1"
    var port: Int = 8818
    var connectionMode: ConnectionMode = ConnectionMode.SERVER
    var autoReconnect: Boolean = true
    var stopOnEntry: Boolean = false
    var pathMappings: MutableList<PathMapping> = mutableListOf()
    var luaPandaScriptPath: String = ""
    /** Whether to enable the libpdebug C hook library.
     *  When true, LuaPanda.lua tries to load libpdebug (from [clibPath] if set, otherwise the bundled one),
     *  which improves hook performance 10-100x vs pure Lua debug.sethook.
     *  When false (default), pure Lua debug is used — slower but compatible with any Lua runtime (LuaJIT, Lua 5.2, custom builds, etc).
     *  Default is false because the C hook library often fails to load in custom/embedded Lua environments,
     *  Default: true — the plugin bundles precompiled libpdebug for Lua 5.1/5.3/5.4 on Win x86/x64 and Mac x64/arm64,
     *  which covers the vast majority of users (same-machine debugging on Windows/Mac with standard Lua).
     *  Users who hit loader failures (LuaJIT / Lua 5.2 / remote debugging / unusual builds) should uncheck it.
     *  When checked but load fails, LuaPanda.lua falls back to pure Lua debug and the plugin prints a WARNING. */
    var useCHook: Boolean = true
    /** Path to the directory containing the libpdebug C hook library (e.g. ".../LuaPanda/Debugger/debugger_lib/").
     *  Only used when [useCHook] is true. If empty but [useCHook] is true, the plugin-bundled libpdebug is used. */
    var clibPath: String = ""
    /** Handshake fallback timeout in milliseconds.
     *  After sending initSuccess, if LuaPanda.lua hasn't replied within this window AND no other message
     *  has been received, we send breakpoints as a safety-net fallback.
     *
     *  The default (3000ms) is tuned for **remote debugging** (physical / virtual machines over a LAN),
     *  where RTT + Lua script-load time can easily exceed 500ms. On pure localhost loopback the reply
     *  typically arrives in <20ms, so this delay is effectively invisible.
     *
     *  Setting this too low on remote connections causes a documented race: the fallback fires
     *  `setBreakPoint` while LuaPanda.lua is still inside its initSuccess handler (uninitialised state),
     *  resulting in `SocketException: Connection reset`. See docs/UserGuide.md troubleshooting section. */
    var handshakeFallbackMs: Int = 3000

    enum class ConnectionMode {
        SERVER,  // IDE listens, Lua connects
        CLIENT   // IDE connects to Lua (reverse mode for real device)
    }

    data class PathMapping(
        var localRoot: String = "",
        var remoteRoot: String = ""
    )

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return LuaPandaRunConfigurationEditor(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        // Only support Debug executor — return null for Run to grey out the Run button
        if (executor.id != DefaultDebugExecutor.EXECUTOR_ID) {
            return null
        }
        return LuaPandaRunProfileState(this, environment)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        host = element.getAttributeValue("host") ?: "127.0.0.1"
        port = element.getAttributeValue("port")?.toIntOrNull() ?: 8818
        connectionMode = try {
            ConnectionMode.valueOf(element.getAttributeValue("connectionMode") ?: "SERVER")
        } catch (_: Exception) { ConnectionMode.SERVER }
        autoReconnect = element.getAttributeValue("autoReconnect")?.toBoolean() ?: true
        stopOnEntry = element.getAttributeValue("stopOnEntry")?.toBoolean() ?: false
        luaPandaScriptPath = element.getAttributeValue("luaPandaScriptPath") ?: ""
        useCHook = element.getAttributeValue("useCHook")?.toBoolean() ?: true
        clibPath = element.getAttributeValue("clibPath") ?: ""
        handshakeFallbackMs = element.getAttributeValue("handshakeFallbackMs")?.toIntOrNull() ?: 3000

        pathMappings.clear()
        element.getChild("pathMappings")?.getChildren("mapping")?.forEach { mapping ->
            pathMappings.add(PathMapping(
                localRoot = mapping.getAttributeValue("localRoot") ?: "",
                remoteRoot = mapping.getAttributeValue("remoteRoot") ?: ""
            ))
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("host", host)
        element.setAttribute("port", port.toString())
        element.setAttribute("connectionMode", connectionMode.name)
        element.setAttribute("autoReconnect", autoReconnect.toString())
        element.setAttribute("stopOnEntry", stopOnEntry.toString())
        element.setAttribute("luaPandaScriptPath", luaPandaScriptPath)
        element.setAttribute("useCHook", useCHook.toString())
        element.setAttribute("clibPath", clibPath)
        element.setAttribute("handshakeFallbackMs", handshakeFallbackMs.toString())

        if (pathMappings.isNotEmpty()) {
            val mappingsElement = Element("pathMappings")
            for (mapping in pathMappings) {
                val m = Element("mapping")
                m.setAttribute("localRoot", mapping.localRoot)
                m.setAttribute("remoteRoot", mapping.remoteRoot)
                mappingsElement.addContent(m)
            }
            element.addContent(mappingsElement)
        }
    }
}
