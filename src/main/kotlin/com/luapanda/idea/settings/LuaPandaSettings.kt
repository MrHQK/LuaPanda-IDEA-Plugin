package com.luapanda.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent global settings for LuaPanda plugin.
 * Stores paths to external tools (lua-fmt, luacheck) and default configuration.
 */
@State(
    name = "LuaPandaSettings",
    storages = [Storage("LuaPandaSettings.xml")]
)
class LuaPandaSettings : PersistentStateComponent<LuaPandaSettings.State> {

    data class State(
        var luaFmtPath: String = "",
        var luacheckPath: String = "",
        var defaultPort: Int = 8818,
        var defaultHost: String = "127.0.0.1",
        var autoPathMode: Boolean = true,
        var logLevel: String = "INFO"
    )

    private var myState = State()

    var luaFmtPath: String
        get() = myState.luaFmtPath
        set(value) { myState.luaFmtPath = value }

    var luacheckPath: String
        get() = myState.luacheckPath
        set(value) { myState.luacheckPath = value }

    var defaultPort: Int
        get() = myState.defaultPort
        set(value) { myState.defaultPort = value }

    var defaultHost: String
        get() = myState.defaultHost
        set(value) { myState.defaultHost = value }

    var autoPathMode: Boolean
        get() = myState.autoPathMode
        set(value) { myState.autoPathMode = value }

    var logLevel: String
        get() = myState.logLevel
        set(value) { myState.logLevel = value }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): LuaPandaSettings =
            ApplicationManager.getApplication().getService(LuaPandaSettings::class.java)
    }
}
