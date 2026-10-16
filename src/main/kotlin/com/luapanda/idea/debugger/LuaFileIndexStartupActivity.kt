package com.luapanda.idea.debugger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Eagerly pre-scans all .lua files in the project at startup time (in a background coroutine).
 *
 * Without this, the first debug session would trigger a full scan via [LuaFileIndexService],
 * which — while fast (<1s with IDEA's index) — still adds a noticeable delay. By running the
 * scan when the project opens, the cache is already warm by the time the user starts debugging.
 *
 * This is a [ProjectActivity] which runs automatically **after** the project is fully opened
 * and indexes are ready (runs in smart mode by default on 2024.1+). The scan happens on a
 * background coroutine, so it never blocks the EDT or any UI initialization.
 *
 * If the project has no .lua files, the scan completes instantly and the cache stores an
 * empty list — subsequent debug sessions will see an instant cache hit (0ms).
 *
 * Trade-off: uses a small amount of memory (37K strings ≈ 3-5 MB for a large project)
 * even if the user never starts a debug session. This is acceptable because:
 * 1. The plugin is specifically for Lua debugging — users who install it will debug
 * 2. The memory cost is negligible compared to IDEA's own index structures
 * 3. The VFS listener in [LuaFileIndexService] keeps the cache up-to-date for free
 */
class LuaFileIndexStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(LuaFileIndexStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return

        val indexService = LuaFileIndexService.getInstance(project)

        // If already scanned (shouldn't happen on startup, but be defensive)
        if (indexService.isScanned) {
            logger.info("LuaFileIndexStartupActivity: cache already populated, skipping pre-scan")
            return
        }

        val startMs = System.currentTimeMillis()
        logger.info("LuaFileIndexStartupActivity: starting pre-scan of Lua files...")

        try {
            val paths = indexService.scanAndCache()
            val elapsedMs = System.currentTimeMillis() - startMs
            val count = paths?.size ?: 0
            logger.info("LuaFileIndexStartupActivity: pre-scan complete — $count Lua files cached in ${elapsedMs}ms")
        } catch (e: Exception) {
            logger.warn("LuaFileIndexStartupActivity: pre-scan failed (will scan on first debug session)", e)
        }
    }
}
