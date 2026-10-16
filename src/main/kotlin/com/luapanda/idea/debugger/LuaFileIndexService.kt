package com.luapanda.idea.debugger

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Project-level service that caches Lua file paths for efficient path resolution.
 *
 * Previously, every debug session triggered a full scan of all .lua files in the project
 * (37,000+ files), which took hundreds of milliseconds even with IDEA's built-in index.
 * This service maintains a persistent cache across debug sessions, so:
 *
 *   - **First debug session**: full scan (same as before) + register VFS listener
 *   - **Subsequent sessions**: instant — reuse cached paths (0ms scan time)
 *   - **File changes**: VFS listener incrementally adds/removes paths in real-time
 *
 * The cache lives for the duration of the project and is automatically disposed when
 * the project closes.
 *
 * Usage:
 * ```kotlin
 * val service = LuaFileIndexService.getInstance(project)
 * val paths = service.getCachedPaths()  // instant if already scanned
 * if (paths != null) {
 *     pathResolver.registerLocalPaths(paths, alreadyNormalized = true)
 * } else {
 *     // First time — need to scan
 *     service.scanAndCache { cachedPaths ->
 *         pathResolver.registerLocalPaths(cachedPaths, alreadyNormalized = true)
 *     }
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class LuaFileIndexService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(LuaFileIndexService::class.java)

    /**
     * Cached Lua file paths. null means not yet scanned.
     * Paths use forward slashes (IDEA VirtualFile convention) — can be passed directly
     * to registerLocalPaths with alreadyNormalized=true.
     *
     * Thread-safety: reads and writes are synchronized via [lock].
     */
    private var cachedPaths: MutableList<String>? = null
    private val lock = Any()

    /** Whether VFS listener is already registered */
    @Volatile
    private var listenerRegistered = false

    /**
     * Get cached Lua file paths, or null if not yet scanned.
     * Thread-safe.
     */
    fun getCachedPaths(): List<String>? {
        synchronized(lock) {
            return cachedPaths?.toList()
        }
    }

    /**
     * Whether the cache is populated (scan has completed at least once).
     */
    val isScanned: Boolean
        get() = synchronized(lock) { cachedPaths != null }

    /**
     * Scan all Lua files via IDEA's FilenameIndex and cache the results.
     *
     * MUST be called from a background thread (not EDT).
     * After the first call, subsequent calls are no-ops (cache already populated).
     *
     * @return The list of Lua file paths (forward-slash, original case).
     *         Returns null if the scan failed (project disposed, etc.).
     */
    fun scanAndCache(): List<String>? {
        // Double-checked locking: if already scanned, return immediately
        synchronized(lock) {
            cachedPaths?.let { return it.toList() }
        }

        if (project.isDisposed) return null

        try {
            // If somehow called during dumb mode, wait briefly.
            // Callers (StartupActivity runs post-smart-mode; DebugProcess has its own timeout)
            // should normally ensure we're in smart mode already, so this is just a safety net.
            if (DumbService.isDumb(project)) {
                logger.info("LuaFileIndexService: waiting for smart mode (safety net)...")
                val deadline = System.currentTimeMillis() + 15_000L // 15s safety timeout
                while (DumbService.isDumb(project) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                if (DumbService.isDumb(project)) {
                    logger.warn("LuaFileIndexService: still in dumb mode after 15s, aborting scan")
                    return null
                }
            }

            val paths = ReadAction.compute<List<String>, Exception> {
                if (project.isDisposed) return@compute emptyList()
                val scope = GlobalSearchScope.projectScope(project)
                val virtualFiles = FilenameIndex.getAllFilesByExt(project, "lua", scope)
                logger.info("LuaFileIndexService: FilenameIndex returned ${virtualFiles.size} .lua files")
                val result = ArrayList<String>(virtualFiles.size)
                for (vf in virtualFiles) {
                    result.add(vf.path)
                }
                result
            }

            synchronized(lock) {
                cachedPaths = ArrayList(paths)
            }

            // Register VFS listener for incremental updates (only once)
            registerVfsListenerIfNeeded()

            logger.info("LuaFileIndexService: cached ${paths.size} Lua file paths")
            return paths

        } catch (e: Exception) {
            logger.warn("LuaFileIndexService: scan failed", e)
            return null
        }
    }

    /**
     * Register a VirtualFileSystem listener to incrementally update the cache
     * when Lua files are created, deleted, or moved.
     */
    private fun registerVfsListenerIfNeeded() {
        if (listenerRegistered) return
        listenerRegistered = true

        VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
            override fun fileCreated(event: VirtualFileEvent) {
                val file = event.file
                if (file.extension.equals("lua", ignoreCase = true)) {
                    synchronized(lock) {
                        cachedPaths?.add(file.path)
                    }
                    logger.debug("LuaFileIndexService: added ${file.path}")
                }
            }

            override fun fileDeleted(event: VirtualFileEvent) {
                val file = event.file
                if (file.extension.equals("lua", ignoreCase = true)) {
                    synchronized(lock) {
                        cachedPaths?.remove(file.path)
                    }
                    logger.debug("LuaFileIndexService: removed ${file.path}")
                }
            }

            override fun fileMoved(event: VirtualFileMoveEvent) {
                val file = event.file
                if (file.extension.equals("lua", ignoreCase = true)) {
                    val oldPath = event.oldParent.path + "/" + file.name
                    synchronized(lock) {
                        cachedPaths?.remove(oldPath)
                        cachedPaths?.add(file.path)
                    }
                    logger.debug("LuaFileIndexService: moved $oldPath → ${file.path}")
                }
            }

            override fun propertyChanged(event: VirtualFilePropertyEvent) {
                // Handle rename (property "name" changed)
                if (event.propertyName == VirtualFile.PROP_NAME) {
                    val file = event.file
                    val oldName = event.oldValue as? String ?: return
                    val newName = event.newValue as? String ?: return
                    val oldIsLua = oldName.endsWith(".lua", ignoreCase = true)
                    val newIsLua = newName.endsWith(".lua", ignoreCase = true)

                    if (oldIsLua || newIsLua) {
                        synchronized(lock) {
                            if (oldIsLua) {
                                val oldPath = file.parent?.path?.let { "$it/$oldName" }
                                if (oldPath != null) cachedPaths?.remove(oldPath)
                            }
                            if (newIsLua) {
                                cachedPaths?.add(file.path)
                            }
                        }
                        logger.debug("LuaFileIndexService: renamed $oldName → $newName")
                    }
                }
            }
        }, this) // 'this' as Disposable — listener auto-removed when service is disposed
    }

    /**
     * Force a full re-scan (e.g. after path mapping changes).
     * Clears the cache so the next getCachedPaths() returns null.
     */
    fun invalidateCache() {
        synchronized(lock) {
            cachedPaths = null
        }
        logger.info("LuaFileIndexService: cache invalidated")
    }

    override fun dispose() {
        synchronized(lock) {
            cachedPaths = null
        }
        logger.info("LuaFileIndexService: disposed")
    }

    companion object {
        fun getInstance(project: Project): LuaFileIndexService {
            return project.getService(LuaFileIndexService::class.java)
        }
    }
}
