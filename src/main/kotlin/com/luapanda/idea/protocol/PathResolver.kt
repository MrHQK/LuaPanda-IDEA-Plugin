package com.luapanda.idea.protocol

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles path mapping between IDE paths and LuaPanda.lua reported paths.
 *
 * Key concerns:
 * - Windows vs Unix path separators
 * - Case sensitivity differences
 * - Relative vs absolute paths
 * - Auto path matching mode (match by filename when full paths differ)
 * - Path caching for performance
 */
class PathResolver(
    private val pathMappings: List<PathMapping> = emptyList(),
    private val caseSensitive: Boolean = isSystemCaseSensitive(),
    private val autoPathMode: Boolean = true
) {
    private val logger = Logger.getInstance(PathResolver::class.java)

    /** Cache: normalized remote path → local path (original case preserved) */
    private val remoteToLocalCache = ConcurrentHashMap<String, String>()

    /** Cache: normalized local path → remote path */
    private val localToRemoteCache = ConcurrentHashMap<String, String>()

    /**
     * Known local file paths for auto-matching.
     * Key: normalized path (lowercased on Windows) for comparison.
     * Value: original-case path suitable for LocalFileSystem.findFileByPath().
     *
     * We MUST preserve original case because LocalFileSystem.findFileByPath()
     * on Windows requires the exact disk-casing to locate the VirtualFile.
     * Using an all-lowercase path causes findFileByPath to return null,
     * which means getSourcePosition() returns null → IDE never jumps to the breakpoint line.
     *
     * Uses ConcurrentHashMap because scanning (registerLocalPaths) and path resolution
     * (remoteToLocal) may run on different threads simultaneously.
     */
    private val knownLocalPaths = ConcurrentHashMap<String, String>()

    /**
     * Filename-based index for O(1) auto-path matching.
     *
     * Key: normalized filename (lowercased on case-insensitive OS).
     * Value: list of normalized full paths that share this filename.
     *
     * Without this index, every remoteToLocal call scans ALL entries in knownLocalPaths
     * (39,695 entries in the user's project), doing string comparisons on each.
     * With 12-frame stacks and IntelliJ calling getSourcePosition 4× per frame,
     * that's 48 × 39,695 = 1.9M string comparisons per breakpoint hit.
     *
     * This index reduces the lookup to O(1) hash lookup + O(k) where k is the
     * number of files sharing the same name (typically 1-3).
     */
    private val fileNameIndex = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Negative cache for paths that failed to resolve.
     * Avoids repeated File.exists() + File.canonicalPath calls for paths that don't exist.
     * Key: normalized remote path. Value: timestamp when the entry was cached.
     * Entries expire after [NEGATIVE_CACHE_TTL_MS] to handle files being created later.
     */
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val NEGATIVE_CACHE_TTL_MS = 30_000L // 30 seconds

    data class PathMapping(
        val localRoot: String,
        val remoteRoot: String
    )

    /**
     * Register a known local file path for auto path matching.
     * Stores both the normalized key (for comparison) and original path (for findFileByPath).
     * Also updates the filename index for O(1) auto-path lookup.
     */
    fun registerLocalPath(path: String) {
        val originalCasePath = normalizeSeparators(path)
        val normalizedKey = normalizePath(path)
        knownLocalPaths[normalizedKey] = originalCasePath

        // Update filename index
        val fileName = normalizedKey.substringAfterLast('/')
        fileNameIndex.getOrPut(fileName) { java.util.concurrent.CopyOnWriteArrayList() }.add(normalizedKey)

        // Invalidate any negative cache entries that could now be resolved via this path.
        // This handles the race condition where remoteToLocal was called before scanning completed:
        // the path was cached as "not found", but now the file has been registered.
        if (negativeCache.isNotEmpty()) {
            val entriesToRemove = negativeCache.keys.filter { it.substringAfterLast('/') == fileName }
            entriesToRemove.forEach { negativeCache.remove(it) }
        }
    }

    /**
     * Register multiple local paths efficiently in bulk.
     *
     * Builds a local filename index first, then merges into the concurrent maps
     * in one pass. This avoids per-file ConcurrentHashMap/CopyOnWriteArrayList
     * contention when registering 39,695+ files.
     *
     * @param alreadyNormalized If true, the paths already use forward slashes and have no
     *   trailing slash (e.g. VirtualFile.path from IDEA's file index). This skips the
     *   redundant `replace('\\', '/') + trimEnd('/')` on each path, saving ~75K+ string
     *   allocations for a 37K-file project (each path was being normalized twice: once in
     *   normalizeSeparators and once again inside normalizePath which calls normalizeSeparators).
     */
    fun registerLocalPaths(paths: Collection<String>, alreadyNormalized: Boolean = false) {
        // Build local maps first (no concurrency overhead)
        // HashMap capacity: paths.size / 0.75 + 1 avoids rehashing
        val mapCapacity = (paths.size / 0.75).toInt() + 1
        val localPaths = HashMap<String, String>(mapCapacity)
        val localFileNameIndex = HashMap<String, MutableList<String>>(mapCapacity)
        for (path in paths) {
            // When alreadyNormalized, path is already forward-slash with correct casing.
            // We only need to lowercase for the normalized key on case-insensitive systems.
            val originalCasePath: String
            val normalizedKey: String
            if (alreadyNormalized) {
                originalCasePath = path
                normalizedKey = if (!caseSensitive) path.lowercase() else path
            } else {
                originalCasePath = normalizeSeparators(path)
                // normalizePath would call normalizeSeparators again internally;
                // reuse originalCasePath to avoid double-processing
                normalizedKey = if (!caseSensitive) originalCasePath.lowercase() else originalCasePath
            }
            localPaths[normalizedKey] = originalCasePath
            val fileName = normalizedKey.substringAfterLast('/')
            localFileNameIndex.getOrPut(fileName) { mutableListOf() }.add(normalizedKey)
        }
        // Merge into concurrent maps in bulk
        knownLocalPaths.putAll(localPaths)
        for ((fileName, newEntries) in localFileNameIndex) {
            fileNameIndex.getOrPut(fileName) { java.util.concurrent.CopyOnWriteArrayList() }.addAll(newEntries)
        }
        // Invalidate negative cache entries whose filename matches any newly registered file
        if (negativeCache.isNotEmpty()) {
            val registeredFileNames = localFileNameIndex.keys
            val entriesToRemove = negativeCache.keys.filter { key ->
                key.substringAfterLast('/') in registeredFileNames
            }
            entriesToRemove.forEach { negativeCache.remove(it) }
        }
    }

    /**
     * Convert a remote path (from LuaPanda.lua) to a local IDE path.
     *
     * IMPORTANT: The returned path preserves original case (e.g. "D:/Project/test.lua")
     * so that LocalFileSystem.findFileByPath() can locate the VirtualFile on Windows.
     * Previously this returned a lowercased path which caused findFileByPath to fail.
     *
     * NOTE: Lua's debug.getinfo().source prefixes file paths with '@' (indicating "loaded from file").
     * Standard LuaPanda.lua strips this in genUnifiedPath(), but some variants (e.g. UnLua integration)
     * may pass the '@' through. We defensively strip it here to avoid path resolution failures.
     */
    fun remoteToLocal(remotePath: String): String? {
        // Strip Lua source '@' prefix if present (see debug.getinfo docs:
        // "If source starts with '@', the function was defined in a file")
        val cleanedPath = if (remotePath.startsWith("@")) remotePath.substring(1) else remotePath
        val normalized = normalizePath(cleanedPath)

        // Check cache first
        remoteToLocalCache[normalized]?.let { return it }

        // Check negative cache — skip expensive File I/O for paths recently confirmed as non-existent
        negativeCache[normalized]?.let { cachedAt ->
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return null
            } else {
                negativeCache.remove(normalized)
            }
        }

        // Separator-normalized original path (preserves case, only unifies \ → / and trims /)
        // Reused across explicit-mapping and fallback branches to avoid repeated computation.
        val originalNormSep = normalizeSeparators(cleanedPath)

        // Try explicit path mappings — reconstruct the local path preserving original case.
        // We extract the relative part from originalNormSep (not 'normalized') so that
        // sub-directories like "MyModule" keep their casing instead of becoming "mymodule".
        for (mapping in pathMappings) {
            val normalizedRemoteRoot = normalizePath(mapping.remoteRoot)
            if (startsWith(normalized, normalizedRemoteRoot)) {
                // normalizedRemoteRoot.length is a valid offset for originalNormSep because
                // normalizePath only adds a lowercase step on top of normalizeSeparators,
                // and lowercase does not change the length of ASCII path strings.
                val relative = originalNormSep.substring(normalizedRemoteRoot.length).trimStart('/')
                val localRoot = normalizeSeparators(mapping.localRoot)
                val localPath = "$localRoot/$relative"
                remoteToLocalCache[normalized] = localPath
                return localPath
            }
        }

        // Try auto path mode: match by filename using the O(1) filename index
        if (autoPathMode) {
            val fileName = normalized.substringAfterLast('/')

            // O(1) hash lookup instead of O(N) linear scan over all 39,695+ paths
            val candidateKeys = fileNameIndex[fileName]

            if (candidateKeys != null) {
                if (candidateKeys.size == 1) {
                    // Unique filename — direct match (most common case)
                    val result = knownLocalPaths[candidateKeys[0]] ?: candidateKeys[0]
                    remoteToLocalCache[normalized] = result
                    return result
                }

                if (candidateKeys.size > 1) {
                    // Multiple files share this name — find best match by path segments
                    val bestMatch = findBestMatch(normalized, candidateKeys)
                    if (bestMatch != null) {
                        val originalPath = knownLocalPaths[bestMatch] ?: bestMatch
                        remoteToLocalCache[normalized] = originalPath
                        return originalPath
                    }
                }
            }
        }

        // Fallback: if the remote path looks like a valid local path, try to use it directly.
        //
        // IMPORTANT: On Windows, File(path).exists() is case-insensitive — it returns true
        // even for all-lowercase paths like "d:/codebuddyide/project/test.lua".
        // But LocalFileSystem.findFileByPath() REQUIRES the correct disk casing!
        //
        // LuaPanda.lua sends paths through genUnifiedPath() which lowercases everything
        // when pathCaseSensitivity=false. So remotePath is typically all-lowercase on Windows.
        // We MUST resolve it to the real on-disk casing via toRealPath().
        try {
            // toRealPath() = one syscall that checks existence AND resolves canonical casing
            // (replaces the previous two-call pattern: File.exists() + File.canonicalPath)
            val realPath = java.nio.file.Paths.get(cleanedPath).toRealPath()
            val canonicalPath = normalizeSeparators(realPath.toString())
            remoteToLocalCache[normalized] = canonicalPath
            return canonicalPath
        } catch (_: java.nio.file.NoSuchFileException) {
            // File doesn't exist — cache the miss to avoid repeated disk I/O
            negativeCache[normalized] = System.currentTimeMillis()
        } catch (_: Exception) {
            // Other I/O errors — don't cache, allow retry
        }

        logger.debug("Could not resolve remote path: $cleanedPath")
        return null
    }

    /**
     * Convert a local IDE path to a remote path (for LuaPanda.lua).
     *
     * The returned path is normalized (lowercased on Windows) for use in
     * the LuaPanda protocol, where paths are compared case-insensitively.
     */
    fun localToRemote(localPath: String): String? {
        val normalized = normalizePath(localPath)

        // Check cache
        localToRemoteCache[normalized]?.let { return it }

        // Try explicit path mappings (reverse direction)
        for (mapping in pathMappings) {
            val normalizedLocalRoot = normalizePath(mapping.localRoot)
            if (startsWith(normalized, normalizedLocalRoot)) {
                val relative = normalized.substring(normalizedLocalRoot.length).trimStart('/')
                val remotePath = normalizePath(mapping.remoteRoot) + "/" + relative
                localToRemoteCache[normalized] = remotePath
                return remotePath
            }
        }

        // Fallback: return normalized path (lowercased on Windows — matches LuaPanda protocol)
        localToRemoteCache[normalized] = normalized
        return normalized
    }

    /**
     * Clear all caches (call when path mappings change).
     */
    fun clearCache() {
        remoteToLocalCache.clear()
        localToRemoteCache.clear()
        negativeCache.clear()
    }

    // ==================== Internal helpers ====================

    /**
     * Normalize path separators only: backslash → forward slash, trim trailing slash.
     * Does NOT change case. Use this when the original casing must be preserved
     * (e.g. for LocalFileSystem.findFileByPath on Windows).
     */
    private fun normalizeSeparators(path: String): String {
        return path.replace('\\', '/').trimEnd('/')
    }

    /**
     * Fully normalize a path: unify separators + lowercase on case-insensitive OS.
     * Use this for cache keys and comparison operations.
     *
     * Optimization: inlined normalizeSeparators logic to avoid creating an intermediate
     * String object. For paths that are already forward-slash (e.g. from VirtualFile.path),
     * replace('\\', '/') returns the same String instance (no allocation).
     */
    private fun normalizePath(path: String): String {
        val sep = path.replace('\\', '/').trimEnd('/')
        return if (!caseSensitive) sep.lowercase() else sep
    }

    private fun startsWith(path: String, prefix: String): Boolean {
        return if (caseSensitive) path.startsWith(prefix) else path.lowercase().startsWith(prefix.lowercase())
    }

    private fun compareStrings(a: String, b: String): Boolean {
        return if (caseSensitive) a == b else a.equals(b, ignoreCase = true)
    }

    /**
     * Find the best matching local path by comparing path segments from right to left.
     */
    private fun findBestMatch(remotePath: String, candidates: List<String>): String? {
        val remoteSegments = remotePath.split('/').reversed()
        var bestMatch: String? = null
        var bestScore = 0

        for (candidate in candidates) {
            val localSegments = candidate.split('/').reversed()
            var score = 0
            for (i in remoteSegments.indices) {
                if (i < localSegments.size && compareStrings(remoteSegments[i], localSegments[i])) {
                    score++
                } else {
                    break
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = candidate
            }
        }

        return bestMatch
    }

    companion object {
        fun isSystemCaseSensitive(): Boolean {
            val os = System.getProperty("os.name", "").lowercase()
            return !os.contains("win")
        }
    }
}
