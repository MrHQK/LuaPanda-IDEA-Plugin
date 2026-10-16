package com.luapanda.idea.debugger.search

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.luapanda.idea.debugger.LuaPandaDebugProcess
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Variable scopes exposed by LuaPanda.lua's `getVariable` command (varRef ranges). */
enum class LuaVariableScope(val displayName: String, val varRef: Int) {
    LOCAL("Local", 10000),
    UPVALUE("Upvalue", 30000),
    GLOBAL("Global", 20000)
}

/**
 * A variable (or nested table member) found in the current stack frame.
 *
 * @param name  key shown to the user (string keys without quotes)
 * @param path  Lua expression that evaluates to this value, e.g. `self.data["hp max"][1]`
 */
data class LuaVariableEntry(
    val scope: LuaVariableScope,
    val name: String,
    val path: String,
    val type: String,
    val value: String,
    val depth: Int
)

/**
 * Collects all variables of a stack frame, recursively expanding tables up to [maxDepth].
 *
 * Uses the same `getVariable` protocol as the Variables panel, one request at a time
 * (LuaPanda.lua processes requests sequentially while paused). Must be run off the EDT.
 *
 * Every request answered by LuaPanda.lua nests one more `debugger_wait_msg` level on the
 * Lua side until the program resumes, therefore the number of requests is capped by [maxRequests].
 */
class LuaVariableCollector(
    private val debugProcess: LuaPandaDebugProcess,
    private val frameLevel: Int,
    private val scopes: List<LuaVariableScope>,
    private val maxDepth: Int,
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
    private val requestTimeoutMs: Long = 5_000L
) {
    private val logger = Logger.getInstance(LuaVariableCollector::class.java)

    @Volatile
    private var cancelled = false

    @Volatile
    var requestCount: Int = 0
        private set

    /** True when the request cap stopped the traversal before all tables were expanded. */
    @Volatile
    var truncated: Boolean = false
        private set

    /** Non-null when collection failed (disconnected, timeout...). */
    @Volatile
    var errorMessage: String? = null
        private set

    fun cancel() {
        cancelled = true
    }

    private data class PendingTable(
        val scope: LuaVariableScope,
        val path: String,
        val ref: Int,
        val depth: Int
    )

    /**
     * Blocking collection. [onBatch] is invoked (on the calling thread) with the entries
     * produced by each answered request.
     */
    fun collect(onBatch: (List<LuaVariableEntry>) -> Unit) {
        val queue = ArrayDeque<PendingTable>()
        val visitedTables = HashSet<String>()

        for (scope in scopes) {
            if (cancelled) return
            val items = request(scope.varRef, frameLevel) ?: return
            val batch = ArrayList<LuaVariableEntry>(items.size())
            for (element in items) {
                val raw = parseVar(element) ?: continue
                val entry = LuaVariableEntry(scope, raw.name, raw.name, raw.type, raw.value, 0)
                batch.add(entry)
                enqueueIfTable(queue, visitedTables, entry, raw.ref)
            }
            onBatch(batch)
        }

        while (!cancelled) {
            val pending = queue.poll() ?: break
            if (requestCount >= maxRequests) {
                truncated = true
                break
            }
            val items = request(pending.ref, 0) ?: return
            val batch = ArrayList<LuaVariableEntry>(items.size())
            for (element in items) {
                val raw = parseVar(element) ?: continue
                val (name, path) = childNameAndPath(pending.path, raw.name)
                val entry = LuaVariableEntry(pending.scope, name, path, raw.type, raw.value, pending.depth + 1)
                batch.add(entry)
                if (raw.name != METATABLE_KEY) {
                    enqueueIfTable(queue, visitedTables, entry, raw.ref)
                }
            }
            onBatch(batch)
        }
    }

    private fun enqueueIfTable(
        queue: ArrayDeque<PendingTable>,
        visited: MutableSet<String>,
        entry: LuaVariableEntry,
        ref: Int
    ) {
        if (ref <= 0 || entry.depth >= maxDepth) return
        if (!entry.type.equals("table", ignoreCase = true)) return
        val address = TABLE_ADDRESS_REGEX.find(entry.value)?.groupValues?.get(1)
        if (address != null && !visited.add(address)) return
        queue.add(PendingTable(entry.scope, entry.path, ref, entry.depth))
    }

    private fun request(varRef: Int, stackId: Int): JsonArray? {
        if (!debugProcess.isConnected()) {
            errorMessage = "Debugger is not connected"
            return null
        }
        requestCount++
        val future = CompletableFuture<JsonObject>()
        debugProcess.sendGetVariablesRequest(varRef, stackId) { future.complete(it) }
        return try {
            val response = future.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
            val info = response.get("info")
            if (info != null && info.isJsonArray) info.asJsonArray else JsonArray()
        } catch (e: TimeoutException) {
            errorMessage = "Request timed out (varRef=$varRef)"
            null
        } catch (e: Exception) {
            logger.warn("getVariable request failed: varRef=$varRef, stackId=$stackId", e)
            errorMessage = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private data class RawVar(val name: String, val type: String, val value: String, val ref: Int)

    private fun parseVar(element: JsonElement): RawVar? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val name = obj.primitiveString("name") ?: return null
        val type = obj.primitiveString("type") ?: ""
        val value = obj.primitiveString("value") ?: ""
        val ref = obj.primitiveString("variablesReference")?.toIntOrNull() ?: 0
        return RawVar(name, type, value, ref)
    }

    private fun JsonObject.primitiveString(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }

    companion object {
        const val DEFAULT_MAX_REQUESTS = 500
        const val METATABLE_KEY = "_Metatable_"

        /** Matches `table: 0x55d0c8a0` / `table: 000001F3A2B4C5D0` inside "N Members table: ...". */
        private val TABLE_ADDRESS_REGEX = Regex("""table:\s*(\S+)""")
        private val IDENTIFIER_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        private val LUA_KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
            "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
        )

        /**
         * Builds the display name and Lua access expression of a table member.
         * LuaPanda.lua sends string keys wrapped in quotes (`"key"`), other keys via tostring().
         */
        internal fun childNameAndPath(parentPath: String, rawName: String): Pair<String, String> {
            if (rawName == METATABLE_KEY) {
                return rawName to "getmetatable($parentPath)"
            }
            if (rawName.length >= 2 && rawName.startsWith('"') && rawName.endsWith('"')) {
                val key = rawName.substring(1, rawName.length - 1)
                val path = if (IDENTIFIER_REGEX.matches(key) && key !in LUA_KEYWORDS) {
                    "$parentPath.$key"
                } else {
                    "$parentPath[\"${escapeLuaString(key)}\"]"
                }
                return key to path
            }
            return rawName to "$parentPath[$rawName]"
        }

        private fun escapeLuaString(s: String): String {
            val sb = StringBuilder(s.length + 4)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
