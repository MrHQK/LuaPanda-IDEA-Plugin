package com.luapanda.idea.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for PathResolver.
 * Validates path mapping, normalization, caching, and auto-matching.
 */
class PathResolverTest {

    private lateinit var resolver: PathResolver

    @BeforeEach
    fun setup() {
        resolver = PathResolver(
            pathMappings = listOf(
                PathResolver.PathMapping(
                    localRoot = "D:/Projects/MyGame/Assets/Lua",
                    remoteRoot = "/lua"
                )
            ),
            caseSensitive = false,
            autoPathMode = true
        )
    }

    @Test
    fun `test explicit path mapping remote to local`() {
        val local = resolver.remoteToLocal("/lua/game/main.lua")
        assertNotNull(local)
        // After fix: remoteToLocal preserves original case from mapping localRoot
        assertTrue(local!!.contains("MyGame"))
        assertTrue(local.endsWith("game/main.lua"))
    }

    @Test
    fun `test explicit path mapping preserves relative path case`() {
        // The key fix: relative part extracted from original remote path, NOT lowercased 'normalized'
        // Remote path has mixed case sub-directories: "GameLogic/BattleSystem"
        val local = resolver.remoteToLocal("/lua/GameLogic/BattleSystem/combat.lua")
        assertNotNull(local)
        // localRoot case is preserved
        assertTrue(local!!.startsWith("D:/Projects/MyGame/Assets/Lua"))
        // Relative part "GameLogic/BattleSystem" must keep original case, NOT "gamelogic/battlesystem"
        assertTrue(local.contains("GameLogic/BattleSystem"), "Relative path lost original case: $local")
        assertTrue(local.endsWith("GameLogic/BattleSystem/combat.lua"))
    }

    @Test
    fun `test explicit path mapping local to remote`() {
        val remote = resolver.localToRemote("D:/Projects/MyGame/Assets/Lua/game/main.lua")
        assertNotNull(remote)
        assertTrue(remote!!.endsWith("game/main.lua"))
    }

    @Test
    fun `test path normalization`() {
        // Backslashes should be converted to forward slashes
        val local = resolver.localToRemote("D:\\Projects\\MyGame\\Assets\\Lua\\test.lua")
        assertNotNull(local)
        assertFalse(local!!.contains("\\"))
    }

    @Test
    fun `test case insensitive matching preserves original case`() {
        val resolver = PathResolver(caseSensitive = false, autoPathMode = true)
        // Register with ORIGINAL mixed-case path
        resolver.registerLocalPath("D:/Projects/MyGame/Scripts/test.lua")
        // Query with DIFFERENT case
        val result = resolver.remoteToLocal("d:/projects/mygame/scripts/test.lua")
        assertNotNull(result)
        // Result must have the ORIGINAL case (for findFileByPath to work on Windows)
        assertEquals("D:/Projects/MyGame/Scripts/test.lua", result)
    }

    @Test
    fun `test auto path mode returns original case path`() {
        val resolver = PathResolver(caseSensitive = false, autoPathMode = true)
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/Utils/Helper.lua")
        // Remote path has completely different prefix
        val result = resolver.remoteToLocal("/some/other/path/helper.lua")
        assertNotNull(result)
        // Must return the original-case registered path, not a lowercased version
        assertEquals("D:/Projects/MyGame/Assets/Lua/Utils/Helper.lua", result)
    }

    @Test
    fun `test auto path mode best match returns original case`() {
        val resolver = PathResolver(caseSensitive = false, autoPathMode = true)
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/ModuleA/Init.lua")
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/ModuleB/Init.lua")
        val result = resolver.remoteToLocal("/lua/ModuleA/Init.lua")
        assertNotNull(result)
        // Must pick ModuleA and preserve original case
        assertTrue(result!!.contains("ModuleA"), "Should match ModuleA: $result")
        assertEquals("D:/Projects/MyGame/Assets/Lua/ModuleA/Init.lua", result)
    }

    @Test
    fun `test explicit mapping with backslash remote path preserves case`() {
        // Remote path arrives with Windows backslashes
        val local = resolver.remoteToLocal("\\lua\\GameUI\\MainMenu\\panel.lua")
        assertNotNull(local)
        assertTrue(local!!.startsWith("D:/Projects/MyGame/Assets/Lua"))
        assertTrue(local.contains("GameUI/MainMenu"), "Backslash path lost original case: $local")
    }

    @Test
    fun `test auto path mode by filename`() {
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/utils/helper.lua")
        val result = resolver.remoteToLocal("/some/other/path/helper.lua")
        assertNotNull(result)
        assertTrue(result!!.endsWith("helper.lua"))
    }

    @Test
    fun `test auto path mode with multiple matches selects best`() {
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/module_a/init.lua")
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/module_b/init.lua")
        val result = resolver.remoteToLocal("/lua/module_a/init.lua")
        assertNotNull(result)
        assertTrue(result!!.contains("module_a"))
    }

    @Test
    fun `test cache works`() {
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/cached.lua")
        // First call
        val first = resolver.remoteToLocal("/path/to/cached.lua")
        // Second call should use cache
        val second = resolver.remoteToLocal("/path/to/cached.lua")
        assertEquals(first, second)
    }

    @Test
    fun `test clear cache`() {
        resolver.registerLocalPath("D:/Projects/MyGame/Assets/Lua/test.lua")
        resolver.remoteToLocal("/path/test.lua")
        resolver.clearCache()
        // After clearing, should still work (just recalculate)
        val result = resolver.remoteToLocal("/path/test.lua")
        assertNotNull(result)
    }

    @Test
    fun `test unresolvable path returns null`() {
        val resolver = PathResolver(autoPathMode = false)
        val result = resolver.remoteToLocal("/nonexistent/path/to/file.lua")
        assertNull(result)
    }

    @Test
    fun `test null result not cached - late registration still works`() {
        // Simulates the race condition: remoteToLocal called before scanAndRegisterLuaFiles completes
        val resolver = PathResolver(caseSensitive = false, autoPathMode = true)
        // First call — knownLocalPaths is empty, no mapping matches, file doesn't exist → null
        val first = resolver.remoteToLocal("/some/unknown/path/myfile.lua")
        assertNull(first)

        // Now simulate scan completion: register the file
        resolver.registerLocalPath("D:/Projects/MyGame/Scripts/myfile.lua")

        // Second call — should now find it via autoPathMode (null was NOT cached)
        val second = resolver.remoteToLocal("/some/unknown/path/myfile.lua")
        assertNotNull(second, "Result should not be null after registering the path — null was cached incorrectly!")
        assertEquals("D:/Projects/MyGame/Scripts/myfile.lua", second)
    }

    @Test
    fun `test fallback with existing file uses canonical path`() {
        // This test verifies that when a remote path happens to be a valid local file,
        // the resolver uses canonicalPath (correct disk casing) instead of the original
        // (possibly all-lowercase) path from LuaPanda.lua.
        //
        val resolver = PathResolver(caseSensitive = false, autoPathMode = false)

        // Need a file that exists on disk in this checkout — use the repo's own
        // build.gradle.kts, found by walking up from the working directory, so the
        // assertion actually runs on any machine (no hard-coded developer path).
        var root: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        while (root != null && !java.io.File(root, "build.gradle.kts").exists()) {
            root = root.parentFile
        }
        val existingFile = root?.let { java.io.File(it, "build.gradle.kts") }

        assertNotNull(existingFile, "build.gradle.kts not reachable from ${System.getProperty("user.dir")}")

        val result = resolver.remoteToLocal(existingFile!!.path)
        assertNotNull(result, "Fallback should resolve existing file")
        // The result should use canonical path (original disk casing)
        val canonical = existingFile.canonicalPath.replace('\\', '/')
        assertEquals(canonical, result, "Fallback should use canonical (real disk casing) path")
    }

    @Test
    fun `test Chinese path handling`() {
        val resolver = PathResolver(caseSensitive = false, autoPathMode = true)
        resolver.registerLocalPath("D:/项目/我的游戏/脚本/main.lua")
        val result = resolver.remoteToLocal("/脚本/main.lua")
        // Should find it via auto path mode
        assertNotNull(result)
    }
}
