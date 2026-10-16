package com.luapanda.idea

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object LuaPandaIcons {
    @JvmField val LUA_FILE: Icon = IconLoader.getIcon("/icons/lua_file.svg", LuaPandaIcons::class.java)
    @JvmField val LUA_DEBUG: Icon = IconLoader.getIcon("/icons/lua_debug.svg", LuaPandaIcons::class.java)
    @JvmField val LUAPANDA: Icon = IconLoader.getIcon("/icons/luapanda.svg", LuaPandaIcons::class.java)

    // Lua type icons for debugger variable display
    @JvmField val TYPE_NUMBER: Icon = IconLoader.getIcon("/icons/lua_type_number.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_STRING: Icon = IconLoader.getIcon("/icons/lua_type_string.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_BOOLEAN: Icon = IconLoader.getIcon("/icons/lua_type_boolean.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_TABLE: Icon = IconLoader.getIcon("/icons/lua_type_table.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_FUNCTION: Icon = IconLoader.getIcon("/icons/lua_type_function.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_NIL: Icon = IconLoader.getIcon("/icons/lua_type_nil.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_USERDATA: Icon = IconLoader.getIcon("/icons/lua_type_userdata.svg", LuaPandaIcons::class.java)
    @JvmField val TYPE_THREAD: Icon = IconLoader.getIcon("/icons/lua_type_thread.svg", LuaPandaIcons::class.java)

    // Tool window icons
    @JvmField val BREAKPOINT_MANAGER: Icon = IconLoader.getIcon("/icons/breakpoint_manager.svg", LuaPandaIcons::class.java)
}
