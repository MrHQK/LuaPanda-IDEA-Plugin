package com.luapanda.idea.debugger

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.luapanda.idea.actions.SearchLuaVariablesAction
import com.luapanda.idea.protocol.*

/**
 * Core debug process for LuaPanda.
 *
 * Manages the debug session lifecycle, coordinates TCP communication,
 * breakpoint management, and execution control (resume/step/stop).
 *
 * Protocol reference: Tencent/LuaPanda (GitHub)
 *   - IDE → Lua commands: initSuccess, continue, stopOnStep, stopOnStepIn,
 *     stopOnStepOut, stopRun, setBreakPoint, getVariable, getWatchedVariable,
 *     setVariable, runREPLExpression, LuaGarbageCollect
 *   - Lua → IDE events: stopOnCodeBreakpoint, stopOnBreakpoint, stopOnEntry,
 *     stopOnStep, stopOnStepIn, stopOnStepOut, output, debug_console,
 *     tip, tipError, refreshLuaMemory
 */
class LuaPandaDebugProcess(
    session: XDebugSession,
    private val config: LuaPandaRunConfiguration
) : XDebugProcess(session) {

    private val logger = Logger.getInstance(LuaPandaDebugProcess::class.java)

    // ==================== Console & ProcessHandler ====================
    // We hold our own reference to the console and process handler so that
    // printToConsole() does NOT rely on session.consoleView (which can be null
    // during early initialization, causing messages to silently vanish).
    //
    // createConsole() is called by the framework ONCE during XDebugSession.init(),
    // BEFORE sessionInitialized(). After this call, myConsole is guaranteed non-null.
    //
    // ConsoleView.print() is thread-safe — no invokeLater needed.

    private lateinit var myConsole: ConsoleView
    private val myProcessHandler = LuaPandaProcessHandler()

    override fun createConsole(): ExecutionConsole {
        val builder = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
            .createBuilder(session.project)
        myConsole = builder.console
        // Attach the process handler so that the console lifecycle is properly managed
        myConsole.attachToProcess(myProcessHandler)
        myProcessHandler.startNotify()
        logger.info("createConsole: console created and process handler attached")
        return myConsole
    }

    override fun doGetProcessHandler(): ProcessHandler = myProcessHandler

    private val pathResolver = PathResolver(
        pathMappings = config.pathMappings.map {
            PathResolver.PathMapping(it.localRoot, it.remoteRoot)
        },
        autoPathMode = true
    )

    private val breakpointHandlers = createBreakpointHandlers()
    private val editorsProvider = LuaPandaDebuggerEditorsProvider()

    // TCP server/client
    private var server: LuaPandaServer? = null
    private var client: LuaPandaClient? = null
    private var processor: ProtocolProcessor? = null

    // Pending breakpoints to send after connection
    private val registeredBreakpoints = mutableMapOf<String, XLineBreakpoint<*>>()

    /** Cached OS detection — avoids calling System.getProperty on every stopped event / path comparison */
    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    // Breakpoint sync: uses two strategies for optimal latency:
    //
    // 1. **Incremental sync** (single breakpoint change during active debug):
    //    Immediately sends only the affected file's breakpoints, bypassing debounce.
    //    Latency: ~1ms instead of 100ms+.
    //
    // 2. **Full sync with debounce** (session init, bulk toggle, or complex operations):
    //    Waits for a brief window after the last change, then sends ALL breakpoints.
    //    Debounce reduced from 100ms to 16ms (~1 frame at 60fps) — fast enough to
    //    batch rapid changes but near-imperceptible delay for single operations.
    //
    // The scheduler is also reused as the safety-net timer for handshake fallback.
    private val bpSyncScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LuaPanda-BP-Sync").apply { isDaemon = true }
    }
    @Volatile
    private var bpSyncPending: java.util.concurrent.ScheduledFuture<*>? = null

    /** Tracks files that have pending incremental changes (for delta sync) */
    private val pendingDirtyFiles = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Volatile
    private var connected = false

    /** True once stop() has been called – prevents disconnect callbacks from re-entering stop logic */
    @Volatile
    private var stopping = false

    /** Consecutive short-lived (<2s) connection count. Used to surface a one-shot hint when the
     *  Lua host is clearly churning (repeated LuaPanda.stop()/start() or script hot-reload). */
    @Volatile
    private var shortLivedConnectionCount: Int = 0

    /** Consecutive short-lived connections where Lua never sent the initSuccess reply.
     *  This pattern strongly indicates Lua side `connectTimeoutSec` (default 5ms) is too small
     *  for the network RTT, causing Lua to abandon the TCP socket before IDE's initSuccess
     *  command arrives. See diagnostic message emitted from onClientDisconnected. */
    @Volatile
    private var silentHandshakeFailCount: Int = 0

    /** Set to true when handleInitSuccessReply() fires for the current connection.
     *  Reset on each new connection (in onDebuggerConnected). Used by the disconnect path
     *  to distinguish "Lua never replied to initSuccess" vs. "Lua replied then got disconnected". */
    @Volatile
    private var receivedInitSuccessReply: Boolean = false

    /** Whether the "connectTimeoutSec too small" hint has already been printed for the
     *  current session. Prevents spamming the console if the user ignores the hint. */
    @Volatile
    private var connectTimeoutHintShown: Boolean = false

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = breakpointHandlers

    /**
     * Create breakpoint handlers for all available Lua breakpoint types.
     * Always includes our own LuaPandaBreakpointType handler.
     * If EmmyLua is installed, also includes a handler for EmmyLua's "lua-line" type
     * so that breakpoints set via EmmyLua's gutter are handled by our debugger.
     */
    private fun createBreakpointHandlers(): Array<XBreakpointHandler<*>> {
        val handlers = mutableListOf<XBreakpointHandler<*>>()
        handlers.add(LuaPandaBreakpointHandler(this))
        // Add an additional handler for EmmyLua's breakpoint type if it exists and differs from ours
        try {
            val emmyType = XBreakpointType.EXTENSION_POINT_NAME.extensions.find { it.id == "lua-line" }
            if (emmyType != null) {
                @Suppress("UNCHECKED_CAST")
                val emmyTypeClass = emmyType.javaClass as Class<out XBreakpointType<XLineBreakpoint<*>, *>>
                handlers.add(EmmyLuaBreakpointHandlerAdapter(this, emmyTypeClass))
            }
        } catch (_: Exception) {
            // EmmyLua not available
        }
        return handlers.toTypedArray()
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun sessionInitialized() {
        super.sessionInitialized()

        logger.info("sessionInitialized: starting debug session setup")

        // Start TCP connection IMMEDIATELY on a background thread.
        // File scanning runs in parallel on a separate thread — it does NOT block the connection.
        //
        // Previous bug: scanning 37,000+ Lua files took ~98 seconds, during which
        // the Console was blank and the connection was not established. Users would
        // see an empty Debug Console and assume the plugin was broken.
        //
        // Path resolution (remoteToLocal) will work without the scan results — it
        // falls back to direct path matching. Once scanning completes, auto-path
        // matching becomes available for subsequent breakpoint hits.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (stopping) {
                logger.info("sessionInitialized: stopping=true, aborting connection")
                return@executeOnPooledThread
            }
            startConnection()
        }

        // Scan Lua files in a separate background thread (non-blocking)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                logger.info("sessionInitialized: beginning Lua file scan (non-blocking)")
                printToConsole("Scanning project Lua files for path matching...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                val startMs = System.currentTimeMillis()
                val source = scanAndRegisterLuaFiles()
                val elapsedMs = System.currentTimeMillis() - startMs
                logger.info("sessionInitialized: scan complete in ${elapsedMs}ms (source=$source)")
                // Show user-friendly output distinguishing cached vs fresh scan
                printToConsole("Lua file scan complete — $source (${elapsedMs}ms)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            } catch (e: Exception) {
                logger.warn("sessionInitialized: scan failed", e)
            }
        }
    }

    /**
     * Scan the project for .lua files and register their paths with the PathResolver
     * for auto-path matching.
     *
     * Uses IntelliJ's built-in file index ([FilenameIndex]) instead of manually walking
     * the file system. The platform index is maintained incrementally by IDEA, so queries
     * are typically completed in milliseconds — even for projects with 30,000+ Lua files.
     *
     * Previous implementation used `java.io.File.walkTopDown()` which took ~98 seconds
     * for 37,808 files. The index-based implementation completes in <1 second.
     * Now with [LuaFileIndexService] project-level caching, subsequent debug sessions
     * skip the scan entirely (0ms) — cached paths are reused and incrementally updated
     * via VirtualFileSystem listener.
     *
     * MUST be called from a background thread — never on the EDT.
     *
     * If IDEA is still indexing (dumb mode), we wait up to [DUMB_MODE_TIMEOUT_MS] then
     * fall back to the filesystem walk.
     */
    /** Maximum time (ms) to wait for IDEA indexing before falling back to filesystem walk */
    private val DUMB_MODE_TIMEOUT_MS = 10_000L // 10 seconds

    /**
     * @return a human-readable scan source string for Console output (e.g. "cached", "index", "fallback")
     */
    private fun scanAndRegisterLuaFiles(): String {
        val project = session.project
        if (project.isDisposed) return "skipped"

        // === Strategy 1: Use project-level cache (instant for 2nd+ debug sessions) ===
        val indexService = LuaFileIndexService.getInstance(project)
        val cached = indexService.getCachedPaths()
        if (cached != null) {
            // Cache hit — skip the entire scan
            if (cached.isNotEmpty()) {
                pathResolver.registerLocalPaths(cached, alreadyNormalized = true)
                logger.info("scanAndRegisterLuaFiles: used cached ${cached.size} paths (0ms scan)")
            }
            return "cached, ${cached.size} files"
        }

        // === Strategy 2: First debug session — full scan via IDEA index ===
        try {
            // Wait for IDEA indexing to finish, but with a timeout.
            if (DumbService.isDumb(project)) {
                logger.info("scanAndRegisterLuaFiles: IDEA is still indexing, waiting up to ${DUMB_MODE_TIMEOUT_MS}ms...")
                printToConsole("Waiting for IDEA indexing (up to ${DUMB_MODE_TIMEOUT_MS / 1000}s)...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                val deadline = System.currentTimeMillis() + DUMB_MODE_TIMEOUT_MS
                while (DumbService.isDumb(project) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                if (DumbService.isDumb(project)) {
                    logger.info("scanAndRegisterLuaFiles: dumb mode timeout after ${DUMB_MODE_TIMEOUT_MS}ms, using filesystem fallback")
                    printToConsole("IDEA indexing still in progress, using filesystem scan instead\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    scanAndRegisterLuaFilesFallback()
                    return "fallback"
                }
                logger.info("scanAndRegisterLuaFiles: smart mode reached")
            }

            // Scan via LuaFileIndexService — results are cached for subsequent sessions
            val luaFiles = indexService.scanAndCache()
            if (luaFiles != null && luaFiles.isNotEmpty()) {
                // VirtualFile.path is already forward-slash normalized, skip separator conversion
                pathResolver.registerLocalPaths(luaFiles, alreadyNormalized = true)
                logger.info("Registered ${luaFiles.size} Lua files for path matching (via IDEA index, now cached)")
            }
            return "index, ${luaFiles?.size ?: 0} files"
        } catch (e: Exception) {
            logger.warn("scanAndRegisterLuaFiles: index query failed, falling back to filesystem walk", e)
            scanAndRegisterLuaFilesFallback()
            return "fallback"
        }
    }

    /** Directories to skip during Lua file scanning (case-insensitive) — used by fallback only */
    private val SCAN_EXCLUDED_DIRS = setOf(
        ".git", ".svn", ".hg", ".idea", ".vscode", ".codebuddy",
        "node_modules", "build", "out", "dist", ".gradle", "__pycache__"
    )

    /**
     * Fallback: scan the filesystem directly when IDEA's index is unavailable.
     * This is only used when [scanAndRegisterLuaFiles] fails (e.g. project disposed during indexing).
     */
    private fun scanAndRegisterLuaFilesFallback() {
        try {
            val projectDir = session.project.basePath ?: return
            val luaFiles = mutableListOf<String>()
            val dir = java.io.File(projectDir)
            if (dir.isDirectory) {
                dir.walkTopDown()
                    .onEnter { d ->
                        !SCAN_EXCLUDED_DIRS.contains(d.name.lowercase())
                    }
                    .filter { it.isFile && it.extension.equals("lua", ignoreCase = true) }
                    .forEach { luaFiles.add(it.absolutePath) }
            }
            if (luaFiles.isNotEmpty()) {
                pathResolver.registerLocalPaths(luaFiles)
                logger.info("Registered ${luaFiles.size} Lua files for path matching (via filesystem fallback)")
            }
        } catch (e: Exception) {
            logger.warn("Failed to scan Lua files via filesystem fallback", e)
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        sendCommand(ProtocolConstants.CMD_STEP_OVER)
    }

    override fun startStepInto(context: XSuspendContext?) {
        sendCommand(ProtocolConstants.CMD_STEP_INTO)
    }

    override fun startStepOut(context: XSuspendContext?) {
        sendCommand(ProtocolConstants.CMD_STEP_OUT)
    }

    override fun resume(context: XSuspendContext?) {
        logger.debug("resume: sending continue command, connected=$connected, processor=${processor != null}")
        sendCommand(ProtocolConstants.CMD_CONTINUE)
    }

    override fun stop() {
        if (stopping) return
        stopping = true
        val wasConnected = connected
        connected = false

        // Cancel any pending debounced breakpoint sync
        bpSyncPending?.cancel(false)
        pendingDirtyFiles.clear()
        bpSyncScheduler.shutdownNow()

        // Capture references before nulling them — stop() may be called on the EDT,
        // so we must NOT block it (no Thread.sleep, no synchronous I/O).
        val proc = processor
        val srv = server
        val cli = client
        server = null
        client = null
        processor = null

        // Perform TCP cleanup on a background thread to avoid any EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (wasConnected && proc != null) {
                    // CRITICAL: We must send continue + stopRun AND wait long enough
                    // for LuaPanda.lua to process them BEFORE closing the socket.
                    //
                    // LuaPanda.lua behavior:
                    //   - In PAUSED state (debugger_wait_msg blocking): immediately receives
                    //     and processes messages from TCP. continue unblocks it, then
                    //     stopRun makes it call disconnect() and remove debug hooks.
                    //   - In RUN state: only reads TCP messages via debugger_wait_msg(0)
                    //     in debug_hook's non-blocking check, which happens every ~1 second
                    //     (controlled by receiveMsgTimer). Each call processes ONE message
                    //     from TCP/recvMsgQueue.
                    //
                    // KEY TIMING:
                    //   - Send continue + stopRun back-to-back (no sleep between them)
                    //     so they arrive in the same TCP segment. LuaPanda.lua's
                    //     receiveMessage() has sticky-packet handling that splits multiple
                    //     messages from one TCP read. BUT debugger_wait_msg(0) only calls
                    //     receiveMessage once, so only one message is processed per hook cycle.
                    //   - In PAUSED state: both messages processed immediately (continue
                    //     returns from debugger_wait_msg, Lua re-enters hook, next
                    //     debugger_wait_msg(0) reads stopRun).
                    //   - In RUN state: continue is processed in the first
                    //     debugger_wait_msg(0) (no-op since already RUN), stopRun in the
                    //     second call ~1s later. We must keep socket open long enough.
                    //
                    // Wait 2 seconds after sending to cover two 1-second check cycles,
                    // ensuring BOTH messages are processed before socket closure.
                    //
                    // Previous bug: waited only 50ms then immediately closed socket,
                    // causing TCP RST that discarded buffered data before Lua could read it.
                    logger.info("stop: sending continue + stopRun to Lua...")
                    try { proc.sendCommand(ProtocolConstants.CMD_CONTINUE) } catch (e: Exception) {
                        logger.info("stop: continue send failed: ${e.message}")
                    }
                    try { proc.sendCommand(ProtocolConstants.CMD_STOP) } catch (e: Exception) {
                        logger.info("stop: stopRun send failed: ${e.message}")
                    }
                }
            } catch (_: Exception) {
            }
            // Notify the process handler immediately so the Debug window shows
            // "Process finished" without waiting for the TCP grace period.
            try { myProcessHandler.destroyProcess() } catch (_: Exception) {}

            // Grace period: keep socket open for 2 seconds so Lua can process
            // continue + stopRun via its periodic debugger_wait_msg(0) checks.
            // In RUN state, Lua checks for messages every ~1s, so 2s covers two cycles
            // to ensure both continue and stopRun are processed.
            // In PAUSED state (breakpoint hit), both messages are processed immediately.
            //
            // After the grace period, force-close the TCP connection. Even if Lua
            // didn't process stopRun, socket closure will trigger disconnect() on
            // the Lua side when it next tries sock:receive or sock:send.
            if (wasConnected) {
                logger.info("stop: waiting 2s grace period for Lua to process shutdown commands...")
                try { Thread.sleep(2000) } catch (_: Exception) {}
                logger.info("stop: grace period elapsed, force-closing connections")
            }

            // Shut down TCP server / client — this also unblocks accept() and readLoop()
            srv?.stop()
            cli?.disconnect()
        }
    }

    override fun startPausing() {
        // LuaPanda does not have a dedicated pause command;
        // sending stopOnStep achieves a similar effect (stop at next line)
        sendCommand(ProtocolConstants.CMD_STEP_OVER)
    }

    // ==================== Breakpoint Management ====================

    fun registerBreakpoint(breakpoint: XLineBreakpoint<*>) {
        val position = breakpoint.sourcePosition ?: run {
            logger.warn("registerBreakpoint: breakpoint has no sourcePosition!")
            return
        }
        val filePath = position.file.path
        val line = position.line + 1 // LuaPanda uses 1-based lines

        val remotePath = pathResolver.localToRemote(filePath) ?: filePath
        logger.info("registerBreakpoint: local='$filePath' -> remote='$remotePath', line=$line, connected=$connected")

        val key = "$remotePath:$line"
        registeredBreakpoints[key] = breakpoint

        if (connected) {
            scheduleBpSyncForFile(remotePath)
        }
    }

    fun unregisterBreakpoint(breakpoint: XLineBreakpoint<*>) {
        val position = breakpoint.sourcePosition ?: return
        val filePath = position.file.path
        val line = position.line + 1
        val remotePath = pathResolver.localToRemote(filePath) ?: filePath
        val key = "$remotePath:$line"
        registeredBreakpoints.remove(key)

        if (connected) {
            scheduleBpSyncForFile(remotePath)
        }
    }

    /**
     * Schedule an incremental breakpoint sync for a specific file.
     *
     * Uses a short 16ms debounce (≈1 frame at 60fps) to batch rapid changes to the
     * SAME file (e.g. user quickly toggling multiple breakpoints in one file), while
     * still feeling near-instant for typical single breakpoint operations.
     *
     * LuaPanda.lua's setBreakPoint handler works per-file (`breaks[path] = bks`),
     * so sending only the changed file's breakpoints is safe and efficient — no need
     * to re-send ALL breakpoints across ALL files on every change.
     *
     * Compared to the previous 100ms full-sync debounce:
     *   - Single BP toggle: ~100ms → ~16ms (6x faster, near-instant feel)
     *   - Bulk changes (10 BPs): still batched via 16ms window, one flush per file
     */
    private fun scheduleBpSyncForFile(remotePath: String) {
        pendingDirtyFiles.add(remotePath)
        bpSyncPending?.cancel(false)
        bpSyncPending = bpSyncScheduler.schedule({
            if (connected && !stopping) {
                sendIncrementalBreakpoints()
            }
        }, 16, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Schedule a full breakpoint sync (all files). Used during handshake and safety-net.
     */
    private fun scheduleFullBpSync(delayMs: Long = 16) {
        bpSyncPending?.cancel(false)
        pendingDirtyFiles.clear() // full sync supersedes any pending incremental sync
        bpSyncPending = bpSyncScheduler.schedule({
            if (connected && !stopping) {
                sendSetBreakpoints()
            }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Send only the changed files' breakpoints — an incremental (delta) sync.
     *
     * Drains the [pendingDirtyFiles] queue and sends a setBreakPoint message
     * for each unique dirty file, containing that file's current breakpoints.
     * If a file's breakpoints were all removed, sends an empty bks array to
     * clear them on the Lua side.
     *
     * This avoids re-serializing and re-transmitting breakpoints for ALL files
     * when only one or two files changed — a significant reduction in TCP I/O
     * for projects with many breakpoints spread across many files.
     */
    private fun sendIncrementalBreakpoints() {
        // Drain dirty file queue into a unique set
        val dirtyFiles = mutableSetOf<String>()
        while (true) {
            val file = pendingDirtyFiles.poll() ?: break
            dirtyFiles.add(file)
        }

        if (dirtyFiles.isEmpty()) return

        logger.info("sendIncrementalBreakpoints: ${dirtyFiles.size} dirty file(s)")

        // Build per-file breakpoint maps (only for dirty files)
        val messageBatch = mutableListOf<JsonObject>()
        val consoleSb = StringBuilder()

        for (dirtyFile in dirtyFiles) {
            val bpList = mutableListOf<JsonObject>()
            for ((key, bp) in registeredBreakpoints) {
                val parts = key.split(":")
                if (parts.size >= 2) {
                    val file = parts.dropLast(1).joinToString(":")
                    if (file == dirtyFile) {
                        val line = parts.last().toIntOrNull() ?: 0
                        val condition = bp.conditionExpression?.expression
                        val logMessage = bp.logExpressionObject?.expression
                        val type = when {
                            !condition.isNullOrBlank() -> "0"
                            !logMessage.isNullOrBlank() -> "1"
                            else -> "2"
                        }
                        val bpObj = JsonObject().apply {
                            addProperty("line", line)
                            addProperty("type", type)
                            if (!condition.isNullOrBlank()) addProperty("condition", condition)
                            if (!logMessage.isNullOrBlank()) addProperty("logMessage", logMessage)
                        }
                        bpList.add(bpObj)
                    }
                }
            }

            val bksArray = JsonArray()
            bpList.forEach { bksArray.add(it) }

            val info = JsonObject().apply {
                addProperty("path", dirtyFile)
                add("bks", bksArray)
            }
            val msg = JsonObject().apply {
                addProperty("cmd", ProtocolConstants.CMD_SET_BREAKPOINTS)
                addProperty("callbackId", "0")
                add("info", info)
            }
            messageBatch.add(msg)

            for (bp in bpList) {
                val line = bp.get("line")?.asInt ?: 0
                consoleSb.append("  BP: $dirtyFile:$line\n")
            }
            logger.info("Incremental sync: ${bpList.size} breakpoint(s) for $dirtyFile")
        }

        // Send all dirty-file messages in one batch (single flush)
        if (messageBatch.isNotEmpty()) {
            processor?.sendMessages(messageBatch)
        }
        if (consoleSb.isNotEmpty()) {
            printToConsole(consoleSb.toString(), ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }

    // ==================== Connection ====================

    private fun startConnection() {
        if (stopping) {
            logger.info("startConnection: stopping=true, aborting")
            return
        }
        if (config.connectionMode == LuaPandaRunConfiguration.ConnectionMode.SERVER) {
            startServer()
        } else {
            startClient()
        }
    }

    private fun startServer() {
        printToConsole("Waiting for LuaPanda connection on port ${config.port}...\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        server = LuaPandaServer(config.port, object : LuaPandaServer.ServerListener {
            override fun onClientConnected(processor: ProtocolProcessor) {
                // NOTE: Do NOT print "LuaPanda connected" here — this callback may fire
                // for a "zombie socket" (Lua abandoned it due to `connectTimeoutSec=0.005`
                // timeout while the kernel still completed SYN-ACK). Zombies get silently
                // replaced by the Server layer. We only announce success once Lua actually
                // replies to our initSuccess — see handleInitSuccessReply().
                this@LuaPandaDebugProcess.processor = processor
                connected = true
                // Protocol handshake: initSuccess must be sent first, then breakpoints
                onDebuggerConnected()
            }

            override fun onClientDisconnected(reason: String, connectionDurationMs: Long) {
                connected = false
                processor = null
                // Cancel any pending handshake fallback — otherwise it will fire into
                // the NEXT connection's handshake window and cause Connection reset loops.
                handshakeFallbackFuture?.cancel(false)
                handshakeFallbackFuture = null
                // Print disconnect reason and connection lifetime so users can diagnose
                // frequent-reconnect issues (e.g. Lua host running LuaPanda.stop()/start() in a loop).
                printToConsole(
                    "LuaPanda disconnected (after ${connectionDurationMs}ms): $reason\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
                logger.info("onClientDisconnected: reason=[$reason], duration=${connectionDurationMs}ms")

                // Heuristic: a connection that closes within ~2 seconds tells us something
                // is wrong. We distinguish TWO failure patterns:
                //
                //   A) Lua NEVER sent initSuccess reply (receivedInitSuccessReply == false)
                //      → In the NEW concurrent-accept design this branch is effectively
                //        unreachable: pre-handshake "zombie" sockets are filtered out by
                //        LuaPandaServer and never invoke this callback at all. We keep
                //        the branch as a safety net in case the Lua peer closes the socket
                //        *after* we sent initSuccess but *before* any reply arrives AND
                //        the flag was somehow reached — extremely unlikely now.
                //
                //   B) Lua DID send initSuccess reply then got disconnected shortly after
                //      → Lua host is churning: script hot-reload, repeated LuaPanda.stop()
                //        + start(), or init code runs every frame.
                if (connectionDurationMs in 1..2000) {
                    shortLivedConnectionCount++
                    if (!receivedInitSuccessReply) {
                        silentHandshakeFailCount++
                        if (silentHandshakeFailCount == 3 && !connectTimeoutHintShown) {
                            connectTimeoutHintShown = true
                            emitConnectTimeoutHint()
                        } else if (silentHandshakeFailCount == 10) {
                            // User ignored the first hint. Upgrade to ERROR with a clearer
                            // concrete patch suggestion — this pattern will otherwise loop
                            // for thousands of cycles (seen in the wild: 10k+ reconnects
                            // over 2h53m before an RTT jitter happened to fit 5ms).
                            emitConnectTimeoutHintEscalated()
                        }
                    } else if (shortLivedConnectionCount == 3) {
                        printToConsole(
                            "[LuaPanda] Detected 3 short-lived (<2s) connections in a row — the Lua host " +
                            "is likely reloading scripts or calling LuaPanda.stop()/start() repeatedly.\n" +
                            "  Common causes:\n" +
                            "    - Hot-reload/reimport of the script that calls LuaPanda.start(...)\n" +
                            "    - The init code path is executed on every frame/event\n" +
                            "    - A watchdog in the host re-initializes Lua state periodically\n" +
                            "  To diagnose: check Lua host logs for repeated 'require(\"LuaPanda\")' or start() calls.\n",
                            ConsoleViewContentType.LOG_WARNING_OUTPUT
                        )
                    }
                } else if (connectionDurationMs > 10_000) {
                    // Long-lived session: reset counters so we don't nag after a stable run.
                    shortLivedConnectionCount = 0
                    silentHandshakeFailCount = 0
                    connectTimeoutHintShown = false
                }

                if (!stopping && config.autoReconnect) {
                    printToConsole("Waiting for reconnection...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    // The previous session's handshake is done; unfreeze the Server so it
                    // will accept a new connection (and again replace zombies silently).
                    server?.resetHandshakeState()
                } else if (!stopping) {
                    // No auto-reconnect: stop the server and end the debug session.
                    // At this point we are inside handleClient() after readLoop() returned,
                    // so the server thread is NOT blocked in accept(). We can safely call
                    // stop() which will set isRunning=false and close the ServerSocket.
                    // When handleClient() returns, the while(isRunning) loop will exit.
                    printToConsole("Debug session ended\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    server?.stop()
                    ApplicationManager.getApplication().invokeLater {
                        if (!stopping) session.stop()
                    }
                }
            }

            override fun onMessage(message: JsonObject) {
                handleMessage(message)
            }

            override fun onError(error: Exception) {
                logger.warn("Server error", error)
                printToConsole("Error: ${error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }

            override fun onServerStarted(port: Int) {
                logger.info("Server started on port $port")
            }
        })
        server?.start()
    }

    private fun startClient() {
        printToConsole("Connecting to LuaPanda at ${config.host}:${config.port}...\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        client = LuaPandaClient(config.host, config.port, object : LuaPandaClient.ClientListener {
            override fun onConnected(processor: ProtocolProcessor) {
                this@LuaPandaDebugProcess.processor = processor
                connected = true
                printToConsole("Connected to LuaPanda\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                // Protocol handshake: initSuccess must be sent first, then breakpoints
                onDebuggerConnected()
            }

            override fun onDisconnected(reason: String, connectionDurationMs: Long) {
                connected = false
                processor = null
                handshakeFallbackFuture?.cancel(false)
                handshakeFallbackFuture = null
                printToConsole(
                    "Disconnected from LuaPanda (after ${connectionDurationMs}ms): $reason\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
                logger.info("Client mode onDisconnected: reason=[$reason], duration=${connectionDurationMs}ms")
                if (!stopping) {
                    // Connection lost: end the debug session
                    printToConsole("Debug session ended\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    ApplicationManager.getApplication().invokeLater {
                        if (!stopping) session.stop()
                    }
                }
            }

            override fun onMessage(message: JsonObject) {
                handleMessage(message)
            }

            override fun onError(error: Exception) {
                logger.warn("Client error", error)
                printToConsole("Connection error: ${error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        })
        client?.connect()
    }

    // ==================== Message Handling ====================

    /**
     * Dispatch incoming messages from LuaPanda.lua based on the "cmd" field.
     *
     * LuaPanda.lua sends messages with: { "cmd": "<event>", "callbackId": "...", "info": { ... } }
     */
    private fun handleMessage(message: JsonObject) {
        try {
            val cmd = message.get("cmd")?.asString ?: run {
                logger.warn("handleMessage: message has no 'cmd' field! keys=${message.keySet()}")
                return
            }
            // CRITICAL: "info" field may be JsonObject, JsonArray, or even a primitive depending
            // on the command. We must use safe type-checked access to avoid ClassCastException.
            val infoElement = message.get("info")
            val info = if (infoElement != null && infoElement.isJsonObject) infoElement.asJsonObject else null
            if (logger.isDebugEnabled) {
                logger.debug("handleMessage: cmd=$cmd, hasInfo=${info != null}, infoType=${infoElement?.javaClass?.simpleName ?: "null"}, hasStack=${message.has("stack")}")
            }

            when (cmd) {
                // Handshake reply: LuaPanda.lua replies with initSuccess after processing our initSuccess
                ProtocolConstants.CMD_INIT_SUCCESS -> handleInitSuccessReply(info)

                // Breakpoint / stepping stop events
                // IMPORTANT: LuaPanda.lua's SendMsgWithStack puts "stack" at the TOP level of the
                // message (alongside "cmd" and "info"), NOT inside "info". We must always pass the
                // full `message` object so that handleStoppedEvent can find the "stack" array.
                ProtocolConstants.EVT_STOP_ON_CODE_BREAKPOINT -> handleStoppedEvent(message, "breakpoint")
                ProtocolConstants.EVT_STOP_ON_BREAKPOINT -> handleStoppedEvent(message, "breakpoint")
                ProtocolConstants.EVT_STOP_ON_ENTRY -> handleStoppedEvent(message, "entry")
                ProtocolConstants.EVT_STOP_ON_STEP -> handleStoppedEvent(message, "step")
                ProtocolConstants.EVT_STOP_ON_STEP_IN -> handleStoppedEvent(message, "stepIn")
                ProtocolConstants.EVT_STOP_ON_STEP_OUT -> handleStoppedEvent(message, "stepOut")

                // Output events
                ProtocolConstants.EVT_OUTPUT -> handleOutputEvent(info)
                ProtocolConstants.EVT_DEBUG_CONSOLE -> handleDebugConsoleEvent(info)

                // Tip / notification events
                ProtocolConstants.EVT_TIP -> handleTipEvent(info, false)
                ProtocolConstants.EVT_TIP_ERROR -> handleTipEvent(info, true)

                // Memory info
                ProtocolConstants.EVT_REFRESH_LUA_MEMORY -> handleRefreshMemoryEvent(info)

                // setBreakPoint reply from Lua side — log it so we can verify Lua received our breakpoints
                ProtocolConstants.CMD_SET_BREAKPOINTS -> {
                    if (logger.isDebugEnabled) {
                        val msgPreview = message.toString().take(500)
                        logger.debug("handleMessage: setBreakPoint reply from Lua: $msgPreview")
                    }
                }

                // continue reply from Lua side — Lua has acknowledged the continue command
                // and should be resuming execution.
                ProtocolConstants.CMD_CONTINUE -> {
                    logger.debug("handleMessage: continue reply received from Lua — Lua process should now be running")
                    printToConsole("Lua resumed\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }

                // stepOver / stepIn / stepOut replies
                ProtocolConstants.CMD_STEP_OVER, ProtocolConstants.CMD_STEP_INTO, ProtocolConstants.CMD_STEP_OUT -> {
                    logger.debug("handleMessage: $cmd reply received from Lua")
                }

                // stopRun reply — Lua acknowledged the stop command
                ProtocolConstants.CMD_STOP -> {
                    logger.debug("handleMessage: stopRun reply received from Lua — Lua is disconnecting debug hooks")
                }

                else -> {
                    logger.warn("Unhandled command from LuaPanda.lua: $cmd, message preview=${message.toString().take(300)}")
                    // Fallback: try to detect stopped event from message structure
                    if (message.has("stack") || (info != null && info.has("stack"))) {
                        logger.info("handleMessage: detected stack in unhandled command '$cmd', treating as stopped event")
                        handleStoppedEvent(message, "breakpoint")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Error handling message", e)
        }
    }

    private fun handleStoppedEvent(data: JsonObject, reason: String) {
        // LuaPanda.lua's SendMsgWithStack puts "stack" at the TOP level of the message
        // (alongside "cmd", "callbackId", "info"). Each frame has:
        //   { "file": "...", "oPath": "...", "name": "...", "line": "<str>", "index": "<str>" }
        // Note: "line" and "index" are STRING types in LuaPanda protocol.
        //
        // CRITICAL: "info" in stopped events may be a JsonArray (e.g. []), NOT a JsonObject!
        // getAsJsonObject("info") throws ClassCastException on JsonArray, so we must use
        // safe access via get("info") + type checks.
        val infoElement = data.get("info")
        val infoObj = try { if (infoElement != null && infoElement.isJsonObject) infoElement.asJsonObject else null } catch (_: Exception) { null }
        val infoArr = try { if (infoElement != null && infoElement.isJsonArray) infoElement.asJsonArray else null } catch (_: Exception) { null }
        logger.info("handleStoppedEvent: reason=$reason, has stack=${data.has("stack")}")
        if (logger.isDebugEnabled) {
            logger.debug("handleStoppedEvent: data keys=${data.keySet()}, " +
                    "info type=${infoElement?.javaClass?.simpleName ?: "null"}, " +
                    "has info.stack=${infoObj?.has("stack") ?: false}")
            // Only serialize the full JSON for debug-level diagnostics (expensive operation)
            val dataStr = data.toString()
            logger.debug("handleStoppedEvent: data preview=${dataStr.take(500)}")
        }
        val stack = try {
            // Priority 1: "stack" at top level (standard SendMsgWithStack format)
            data.getAsJsonArray("stack")
                // Priority 2: "stack" inside info object
                ?: infoObj?.getAsJsonArray("stack")
                // Priority 3: if info itself is an array, it might BE the stack
                // (some LuaPanda versions send stack data directly as the info array)
                ?: infoArr?.let { arr ->
                    if (arr.size() > 0 && arr[0].isJsonObject && arr[0].asJsonObject.has("file")) {
                        logger.info("handleStoppedEvent: using info array as stack (${arr.size()} frames)")
                        arr
                    } else null
                }
                ?: run {
                    logger.warn("handleStoppedEvent: no 'stack' array found in message! data=$data")
                    return
                }
        } catch (e: Exception) {
            logger.warn("handleStoppedEvent: error extracting stack array", e)
            return
        }

        if (stack.size() == 0) {
            logger.warn("handleStoppedEvent: stack array is empty")
            return
        }

        logger.info("handleStoppedEvent: stack has ${stack.size()} frame(s)")

        val frames = stack.mapIndexed { arrayIndex, elem ->
            val frame = elem.asJsonObject
            // LuaPanda.lua sends "line" as string, e.g. "10" not 10
            val lineValue = frame.get("line")
            val line = when {
                lineValue == null -> 0
                lineValue.isJsonPrimitive && lineValue.asJsonPrimitive.isNumber -> lineValue.asInt
                lineValue.isJsonPrimitive && lineValue.asJsonPrimitive.isString -> lineValue.asString.toIntOrNull() ?: 0
                else -> 0
            }
            // CRITICAL: "level" here is used as stackId when sending getVariable / getWatchedVariable
            // to LuaPanda.lua. LuaPanda.lua uses: currentCallStack[ stackId - 1 ]
            //
            // VSCode original: uses ss.index (from LuaPanda.lua's getStackTable) directly as frameId.
            // ss.index is computed as: functionLevel - 3 + (hookLib ? 2 : 0) = functionLevel - 1 (hookLib)
            //                          or functionLevel - 3 (pure Lua hook)
            //
            // currentCallStack is built by table.insert() for EVERY functionLevel (including C frames),
            // so currentCallStack[N] corresponds to the (N+2)th functionLevel (when starting at 3).
            // With hookLib: ss.index = functionLevel - 1, so currentCallStack[ss.index - 1]
            //   = currentCallStack[functionLevel - 2] which correctly maps to the right entry.
            //
            // LuaPanda.lua sends ss.index as the "index" field in each stack frame.
            // We MUST use this value (not arrayIndex+1), because when C frames are mixed into
            // currentCallStack but NOT into stackTab, arrayIndex+1 would be wrong.
            val indexValue = frame.get("index")
            val framePosition = when {
                indexValue != null && indexValue.isJsonPrimitive && indexValue.asJsonPrimitive.isNumber -> indexValue.asInt
                indexValue != null && indexValue.isJsonPrimitive && indexValue.asJsonPrimitive.isString -> indexValue.asString.toIntOrNull() ?: (arrayIndex + 1)
                else -> arrayIndex + 1  // fallback: should not happen with standard LuaPanda.lua
            }
            if (logger.isDebugEnabled) {
                logger.debug("handleStoppedEvent: frame[$arrayIndex] index=$indexValue framePosition=$framePosition (arrayIndex+1=${arrayIndex + 1}) " +
                        "file=${frame.get("file")?.asString} line=${frame.get("line")?.asString}")
            }

            StackFrameInfo(
                file = frame.get("file")?.asString ?: "",
                line = line,
                // LuaPanda.lua does NOT send function name in the stack frame;
                // "name" field is hardcoded as "文件名" (useless), try "func" as fallback
                functionName = frame.get("func")?.asString ?: frame.get("name")?.asString ?: "",
                level = framePosition
            )
        }

        val topFrame = frames.first()
        if (logger.isDebugEnabled) {
            logger.debug("handleStoppedEvent: topFrame file='${topFrame.file}', line=${topFrame.line}, " +
                    "func='${topFrame.functionName}', level=${topFrame.level}")
        }
        // Diagnostic: print stack frame info to Console for debugging stackId issues
        printToConsole("[LuaPanda] Paused: ${topFrame.file.substringAfterLast('/')}:${topFrame.line} " +
                "(stackId=${topFrame.level}, ${frames.size} frames)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        // Log path resolution for the top frame
        val resolvedPath = pathResolver.remoteToLocal(topFrame.file)
        if (logger.isDebugEnabled) {
            logger.debug("handleStoppedEvent: remoteToLocal('${topFrame.file}') = '$resolvedPath'")
        }

        // CRITICAL: We must use runInEdt / invokeLater to call session.positionReached / breakpointReached
        // on the EDT (Event Dispatch Thread). These are UI-affecting calls.
        //
        // We also need to guard against the session being already disposed (e.g. user clicked Stop
        // while a stopped event was in flight).
        ApplicationManager.getApplication().invokeLater {
            if (session.isStopped) {
                logger.debug("handleStoppedEvent: session is already stopped, ignoring")
                return@invokeLater
            }

            val suspendContext = LuaPandaSuspendContext(this, frames, pathResolver)

            // IMPORTANT: Validate that the top frame can resolve to a source position.
            // If getSourcePosition() returns null, IntelliJ will show the Frames panel
            // but will NOT navigate to the source file. This happens when:
            //   1. The remote path cannot be resolved to a local file
            //   2. The VirtualFile cannot be found by LocalFileSystem
            //
            // We log this prominently so the user can diagnose path issues.
            val topStackFrame = LuaPandaStackFrame(this, topFrame, pathResolver)
            val sourcePos = topStackFrame.getSourcePosition()
            if (sourcePos == null) {
                logger.warn("handleStoppedEvent: WARNING - getSourcePosition returned null for " +
                        "file='${topFrame.file}', resolved='$resolvedPath'. " +
                        "IDE will NOT navigate to source! Check path mappings and file existence.")
                printToConsole("[LuaPanda] WARNING: Cannot locate source file for '${topFrame.file}'. " +
                        "Debug will pause but won't navigate to code. Check path mappings.\n",
                    ConsoleViewContentType.ERROR_OUTPUT)
            } else {
                logger.info("handleStoppedEvent: sourcePosition resolved: file=${sourcePos.file.path}, line=${sourcePos.line}")
            }

            if (reason == "breakpoint") {
                // Try to find matching breakpoint.
                // registeredBreakpoints keys use remotePath (from pathResolver.localToRemote).
                // topFrame.file comes from LuaPanda.lua's genUnifiedPath - may differ in format.
                // We need to normalize both sides for comparison.
                val bpFile = topFrame.file.replace('\\', '/').trimEnd('/').let {
                    if (isWindows) it.lowercase() else it
                }
                val bpLine = topFrame.line
                val directKey = "$bpFile:$bpLine"

                if (logger.isDebugEnabled) {
                    logger.debug("handleStoppedEvent: breakpoint matching, directKey='$directKey', " +
                            "registeredBreakpoints=${registeredBreakpoints.keys}")
                }

                var breakpoint = registeredBreakpoints[directKey]
                if (breakpoint == null) {
                    // Fuzzy match: compare normalized paths
                    breakpoint = registeredBreakpoints.entries.firstOrNull { (key, _) ->
                        val parts = key.split(":")
                        if (parts.size >= 2) {
                            val regFile = parts.dropLast(1).joinToString(":")
                            val regLine = parts.last().toIntOrNull() ?: -1
                            regLine == bpLine && pathsMatch(regFile, bpFile)
                        } else false
                    }?.value
                    if (breakpoint != null) {
                        logger.debug("handleStoppedEvent: matched breakpoint via fuzzy match")
                    }
                }

                if (breakpoint != null) {
                    logger.debug("handleStoppedEvent: calling breakpointReached")
                    try {
                        session.breakpointReached(breakpoint, null, suspendContext)
                    } catch (e: Exception) {
                        logger.warn("handleStoppedEvent: breakpointReached threw exception, falling back to positionReached", e)
                        try { session.positionReached(suspendContext) } catch (_: Exception) {}
                    }
                    return@invokeLater
                } else {
                    logger.debug("handleStoppedEvent: no matching breakpoint found, falling through to positionReached")
                }
            }
            logger.debug("handleStoppedEvent: calling positionReached")
            try {
                session.positionReached(suspendContext)
            } catch (e: Exception) {
                logger.warn("handleStoppedEvent: positionReached threw exception", e)
            }
        }
    }

    /**
     * Compare two file paths for equality, accounting for:
     * - Path separator differences (/ vs \)
     * - Case insensitivity on Windows
     * - Filename-only matching (autoPathMode)
     */
    private fun pathsMatch(path1: String, path2: String): Boolean {
        val norm1 = path1.replace('\\', '/').trimEnd('/')
        val norm2 = path2.replace('\\', '/').trimEnd('/')

        // Exact match (case-insensitive on Windows)
        if (if (isWindows) norm1.equals(norm2, ignoreCase = true) else norm1 == norm2) return true

        // Filename-only match (for autoPathMode)
        val name1 = norm1.substringAfterLast('/')
        val name2 = norm2.substringAfterLast('/')
        if (if (isWindows) name1.equals(name2, ignoreCase = true) else name1 == name2) {
            // If one path ends with the other (suffix match), it's likely the same file
            val shorter = if (norm1.length < norm2.length) norm1 else norm2
            val longer = if (norm1.length < norm2.length) norm2 else norm1
            if (if (isWindows) longer.endsWith(shorter, ignoreCase = true) else longer.endsWith(shorter)) {
                return true
            }
        }
        return false
    }

    private fun handleOutputEvent(info: JsonObject?) {
        // LuaPanda.lua output: info may contain log text directly or as array items
        val output = info?.get("logInfo")?.asString
            ?: info?.get("output")?.asString
            ?: info?.toString()
            ?: return
        // Log Lua-side output to IDEA log for diagnostics (especially useful with logLevel=0)
        // With logLevel=0, LuaPanda.lua sends detailed hook information including:
        //   - "debug_hook" entries showing event type, line, hookState, runState
        //   - "[lua hook]" entries showing source, currentline, function info
        //   - "HitBreakpoint!" when a breakpoint is detected
        //   - "changeRunState" and "changeHookState" state transitions
        // These are critical for diagnosing why breakpoints aren't hitting.
        if (logger.isDebugEnabled) {
            logger.debug("LuaPanda output: $output")
        }
        // Only print hook-level debug output to console if it's short enough
        // to avoid flooding the console view during logLevel=0
        if (output.length < 1000) {
            printToConsole(output + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
        } else {
            // For very long debug output, truncate in console but keep full in IDEA log
            printToConsole(output.take(500) + "...(truncated, see idea.log)\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    private fun handleDebugConsoleEvent(info: JsonObject?) {
        val output = info?.get("logInfo")?.asString
            ?: info?.toString()
            ?: return
        printToConsole("[DebugConsole] $output\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun handleTipEvent(info: JsonObject?, isError: Boolean) {
        val message = info?.get("logInfo")?.asString
            ?: info?.get("message")?.asString
            ?: info?.toString()
            ?: return
        val type = if (isError) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT
        printToConsole("[LuaPanda] $message\n", type)
    }

    private fun handleRefreshMemoryEvent(info: JsonObject?) {
        val memInfo = info?.get("memInfo")?.asString ?: return
        printToConsole("[Memory] $memInfo\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /**
     * Handle the initSuccess reply from LuaPanda.lua.
     *
     * After we send initSuccess, LuaPanda.lua processes it and replies with:
     *   { "cmd": "initSuccess", "info": { "debuggerVer": "...", "UseHookLib": "...", "UseLoadstring": "...", "isNeedB64EncodeStr": "..." } }
     *
     * TIMING ANALYSIS:
     * When we receive this reply, LuaPanda.lua has just sent it from the initSuccess
     * dataProcess handler. After sending, it executes (when stopOnEntry != "true"):
     *   this.debugger_wait_msg(1)  -- waits up to 1s in WAIT_CMD state for breakpoint messages
     *   this.changeRunState(runState.RUN)
     *
     * During this 1s window, Lua is in WAIT_CMD state. Sending setBreakPoint in
     * WAIT_CMD is SAFE — the handler simply updates the breaks table, sends a reply,
     * and returns (no recursive debugger_wait_msg call). Control returns to the
     * outer debugger_wait_msg(1) loop which continues waiting for more messages.
     *
     * We MUST send breakpoints immediately (during this 1s window) because:
     * 1. If we delay past the 1s window, Lua enters RUN state and starts executing
     *    user code. The first hook call finds breaks table empty and downgrades
     *    to LITE_HOOK, which only listens for return events (no line events).
     * 2. Even though setBreakPoint in RUN state would eventually upgrade the hook,
     *    user code may have already passed the breakpoint line before we send it.
     * 3. By sending during WAIT_CMD, breaks table is populated BEFORE Lua enters
     *    RUN, so the first hook call finds breakpoints and stays in ALL_HOOK.
     */
    private fun handleInitSuccessReply(info: JsonObject?) {
        receivedInitSuccessReply = true
        // Freeze the active socket on the Server side. From now on any extra incoming
        // connection will be closed immediately (matches VSCode's `_server.close()`
        // behaviour, except we keep the listener open so autoReconnect still works
        // after the authenticated session ends).
        server?.markHandshakeComplete()
        // Announce user-visible success ONLY now — previously this was printed on
        // raw TCP accept, which caused "LuaPanda connected / disconnected" flicker
        // on every zombie socket during `connectTimeoutSec=5ms` churn.
        printToConsole("LuaPanda connected\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        // A successful handshake means the previous churn (if any) is resolved.
        // Reset both counters so future, unrelated churns can trigger hints anew.
        silentHandshakeFailCount = 0
        shortLivedConnectionCount = 0
        connectTimeoutHintShown = false
        val debuggerVer = info?.get("debuggerVer")?.asString ?: "unknown"
        val useHookLib = info?.get("UseHookLib")?.asString ?: "unknown"
        printToConsole("LuaPanda debugger version: $debuggerVer (C Hook: $useHookLib)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        logger.info("LuaPanda initSuccess reply: debuggerVer=$debuggerVer, UseHookLib=$useHookLib, info=$info")

        // Performance warning: if hookLib is not loaded, Lua uses pure debug.sethook("lrc")
        // which triggers a hook callback for EVERY LINE of Lua code — extremely slow for large projects.
        // We only warn when the user *intended* to use the C hook (config.useCHook == true) but loading failed.
        // When the user explicitly disabled C hook via Run Configuration, a calm info message is shown instead.
        if (useHookLib == "0") {
            if (config.useCHook) {
                printToConsole(
                    "[LuaPanda] WARNING: libpdebug C hook library not loaded — using pure Lua debug (slow).\n" +
                    "  The plugin bundles precompiled libpdebug for Lua 5.1/5.3/5.4 (Win x86/x64, Mac x64/arm64).\n" +
                    "  Possible reasons for load failure:\n" +
                    "    - Lua runtime version not supported (e.g. LuaJIT, Lua 5.2)\n" +
                    "    - Host application's Lua DLL symbols not compatible with libpdebug\n" +
                    "    - Platform/architecture mismatch (e.g. Linux, or unusual Lua build)\n" +
                    "  Try: set 'libpdebug path' in Run Configuration to a custom-built libpdebug,\n" +
                    "       or uncheck 'Use C hook library' to silence this warning.\n" +
                    "  Run LuaPanda.doctor() in Lua console for detailed diagnosis.\n",
                    ConsoleViewContentType.ERROR_OUTPUT
                )
                logger.warn("handleInitSuccessReply: hookLib NOT loaded! Pure Lua debug.sethook will cause severe performance degradation.")
            } else {
                printToConsole(
                    "[LuaPanda] C hook library disabled by configuration — using pure Lua debug.\n" +
                    "  Tip: enable 'Use C hook library' in Run Configuration for 10-100x faster debugging,\n" +
                    "       if your Lua runtime is Lua 5.1 / 5.3 / 5.4 on Win x86/x64 or Mac x64/arm64.\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
                logger.info("handleInitSuccessReply: C hook disabled by user configuration; pure Lua debug in use.")
            }
        } else {
            printToConsole("[LuaPanda] libpdebug C hook library loaded — high performance mode enabled.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        // Send breakpoints IMMEDIATELY — Lua is in WAIT_CMD state right now,
        // waiting up to 1s for exactly these breakpoint messages.
        //
        // NOTE: We intentionally do NOT check `stopping` here. The `stopping` flag
        // may have been set by a previous session's stop() call that races with
        // this session's handshake (e.g., when the user restarts debugging quickly).
        // As long as `connected` is true, we should complete the handshake properly.
        if (connected && !breakpointsSentAfterHandshake) {
            breakpointsSentAfterHandshake = true
            // Normal success path — cancel the safety-net so it doesn't fire later
            // and interfere with an established debug session.
            handshakeFallbackFuture?.cancel(false)
            handshakeFallbackFuture = null
            logger.info("handleInitSuccessReply: sending breakpoints immediately (during WAIT_CMD window)")
            sendSetBreakpoints()
            logger.info("handleInitSuccessReply: breakpoints sent")
        } else {
            logger.info("handleInitSuccessReply: skipping breakpoint sync (connected=$connected, alreadySent=$breakpointsSentAfterHandshake)")
        }
    }

    // ==================== Protocol Sending ====================

    /**
     * Send a command to LuaPanda.lua via the protocol processor.
     */
    private fun sendCommand(cmd: String, info: JsonObject? = null, callback: ((JsonObject) -> Unit)? = null) {
        processor?.sendCommand(cmd, info, callback)
    }

    /**
     * Send the full breakpoint set to LuaPanda.lua.
     *
     * LuaPanda.lua expects setBreakPoint messages grouped by file path:
     *   { "cmd": "setBreakPoint", "info": { "path": "<file_path>", "bks": [ { "line": <n>, "type": "<type>", "condition": "...", "logMessage": "..." }, ... ] } }
     *
     * Breakpoint types (from VSCode LuaPanda):
     *   "" (empty) = normal line breakpoint
     *   "0" = conditional breakpoint
     *   "1" = logpoint (log message breakpoint)
     *
     * We send one setBreakPoint per file. For files that had breakpoints but now have none,
     * we send an empty bks array to clear them on the Lua side.
     */
    private fun sendSetBreakpoints() {
        logger.info("sendSetBreakpoints: registeredBreakpoints count=${registeredBreakpoints.size}")
        // Group breakpoints by file path
        val bpsByFile = mutableMapOf<String, MutableList<JsonObject>>()
        for ((key, bp) in registeredBreakpoints) {
            val parts = key.split(":")
            if (parts.size >= 2) {
                val file = parts.dropLast(1).joinToString(":") // Handle paths with ':'
                val line = parts.last().toIntOrNull() ?: 0

                // Determine breakpoint type for LuaPanda.lua
                // LuaPanda's C hook library (libpdebug.cpp) uses lua_tointeger to read the type field:
                //   0 = CONDITION_BREAKPOINT → then reads "condition" string (luaL_checkstring)
                //   1 = LOG_POINT → then reads "logMessage" string (luaL_checkstring)
                //   2 = LINE_BREAKPOINT → no extra fields read
                // CRITICAL: Using "" for normal breakpoints causes lua_tointeger("") == 0,
                // which tricks the C library into reading a "condition" field that doesn't exist → crash:
                // "bad argument #-1 to 'sync_breakpoints' (string expected, got nil)"
                val condition = bp.conditionExpression?.expression
                val logMessage = bp.logExpressionObject?.expression
                val type = when {
                    !condition.isNullOrBlank() -> "0"  // Conditional breakpoint (CONDITION_BREAKPOINT)
                    !logMessage.isNullOrBlank() -> "1"  // Logpoint (LOG_POINT)
                    else -> "2"                         // Normal line breakpoint (LINE_BREAKPOINT)
                }

                val bpObj = JsonObject().apply {
                    addProperty("line", line)
                    addProperty("type", type)
                    if (!condition.isNullOrBlank()) addProperty("condition", condition)
                    if (!logMessage.isNullOrBlank()) addProperty("logMessage", logMessage)
                }
                bpsByFile.getOrPut(file) { mutableListOf() }.add(bpObj)
            }
        }

        // Build all messages first, then send in a single batch (one flush)
        // to minimize TCP round-trips during the critical 1-second WAIT_CMD window.
        val messageBatch = mutableListOf<JsonObject>()
        val consoleSb = StringBuilder()

        for ((filePath, bpList) in bpsByFile) {
            val bksArray = JsonArray()
            bpList.forEach { bksArray.add(it) }

            val info = JsonObject().apply {
                addProperty("path", filePath)
                add("bks", bksArray)
            }
            val msg = JsonObject().apply {
                addProperty("cmd", ProtocolConstants.CMD_SET_BREAKPOINTS)
                addProperty("callbackId", "0")
                add("info", info)
            }
            messageBatch.add(msg)
            // Batch console output
            for (bp in bpList) {
                val line = bp.get("line")?.asInt ?: 0
                consoleSb.append("  BP: $filePath:$line\n")
            }
            logger.info("Queued ${bpList.size} breakpoint(s) for file: $filePath")
        }

        // If no breakpoints at all, send an empty setBreakPoint to clear any Lua-side state
        if (bpsByFile.isEmpty()) {
            val info = JsonObject().apply {
                addProperty("path", "")
                add("bks", JsonArray())
            }
            val msg = JsonObject().apply {
                addProperty("cmd", ProtocolConstants.CMD_SET_BREAKPOINTS)
                addProperty("callbackId", "0")
                add("info", info)
            }
            messageBatch.add(msg)
        }

        // Send all messages in one batch (single flush) via processor
        if (messageBatch.isNotEmpty()) {
            processor?.sendMessages(messageBatch)
        }

        // Print batched console output at once
        if (consoleSb.isNotEmpty()) {
            printToConsole(consoleSb.toString(), ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        printToConsole("Synchronized ${registeredBreakpoints.size} breakpoint(s)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /**
     * Send a getWatchedVariable request (used for Evaluate Expression / Watch).
     * Uses callback-based request-response matching via callbackId.
     */
    /**
     * Evaluate result data class — holds value, type, and variablesReference
     * for hover tooltip and evaluate expression support.
     */
    data class EvaluateResult(
        val value: String,
        val type: String,
        val variablesReference: Int
    )

    fun sendEvaluateRequest(expression: String, frameId: Int, callback: (EvaluateResult) -> Unit) {
        logger.debug("sendEvaluateRequest: expression='$expression', frameId=$frameId")
        val info = JsonObject().apply {
            // CRITICAL: LuaPanda.lua's dataProcess "getWatchedVariable" handler reads:
            //   - info.stackId (line 1451): tonumber(dataTable.info.stackId)
            //   - info.varName (line 3042 in processWatchedExp): msgTable.varName
            //
            // VSCode original sends stackId as STRING: arrSend["stackId"] = String(frameId)
            // We match this behavior exactly to avoid any JSON parsing differences on the Lua side.
            addProperty("varName", expression)
            addProperty("stackId", frameId.toString())
        }
        sendCommand(ProtocolConstants.CMD_GET_WATCHED_VARIABLE, info) { response ->
            try {
                // Type-safe access: "info" may not always be a JsonArray
                val infoElem = response.get("info")
                val respInfo = if (infoElem != null && infoElem.isJsonArray) infoElem.asJsonArray else null
                if (logger.isDebugEnabled) {
                    val respPreview = response.toString().take(800)
                    logger.debug("sendEvaluateRequest callback: expression='$expression', frameId=$frameId, respInfo size=${respInfo?.size() ?: "null"}, response=$respPreview")
                }
                if (respInfo != null && respInfo.size() > 0 && respInfo[0].isJsonObject) {
                    val firstVar = respInfo[0].asJsonObject
                    val value = firstVar.get("value")?.asString ?: ""
                    val type = firstVar.get("type")?.asString ?: ""
                    val isSuccess = firstVar.get("isSuccess")?.asString ?: ""
                    // Extract variablesReference for table/userdata/function expansion.
                    // LuaPanda.lua's processWatchedExp sets this to a non-zero value when
                    // the evaluated result is a table/function/userdata, allowing child expansion.
                    // The value may come as either int or string from JSON.
                    val varRefElem = firstVar.get("variablesReference")
                    val variablesReference = when {
                        varRefElem == null -> 0
                        varRefElem.isJsonPrimitive && varRefElem.asJsonPrimitive.isNumber -> varRefElem.asInt
                        varRefElem.isJsonPrimitive && varRefElem.asJsonPrimitive.isString -> varRefElem.asString.toIntOrNull() ?: 0
                        else -> 0
                    }
                    logger.debug("sendEvaluateRequest result: expression='$expression' -> value='$value', type='$type', isSuccess='$isSuccess', variablesReference=$variablesReference")
                    // Diagnostic: if Lua returned an error message, log detailed info to help debug
                    if (isSuccess == "false" || value.contains("错误") || value.contains("未能找到")) {
                        val diagMsg = "[LuaPanda Evaluate] FAILED: expression='$expression', frameId=$frameId, " +
                                "value='$value', type='$type', isSuccess='$isSuccess'\n"
                        logger.warn(diagMsg)
                        printToConsole(diagMsg, ConsoleViewContentType.ERROR_OUTPUT)
                    }
                    // Callback is thread-safe (XDebuggerEvaluator callbacks don't require EDT),
                    // so we invoke directly on the TCP read thread to avoid ~10-30ms invokeLater delay.
                    callback(EvaluateResult(value, type, variablesReference))
                } else {
                    callback(EvaluateResult("nil", "nil", 0))
                }
            } catch (e: Exception) {
                logger.warn("Error parsing evaluate response", e)
                callback(EvaluateResult("error: ${e.message}", "error", 0))
            }
        }
    }

    /**
     * Send a getVariable request for the Variables panel.
     * Uses callback-based request-response matching via callbackId.
     *
     * LuaPanda.lua getVariable protocol (dataProcess "getVariable"):
     *   info.varRef determines what to return:
     *     < 10000  → query a variable reference (child expansion)
     *     10000~19999 → local variables
     *     20000~29999 → global variables
     *     30000+      → upvalues
     *   info.stackId → stack frame index for locals/upvalues
     *
     * VSCode LuaPanda sends:
     *   - For scope "Local":   varRef=10000, stackId=frameId
     *   - For scope "Global":  varRef=20000, stackId=frameId
     *   - For scope "UpValue": varRef=30000, stackId=frameId
     *   - For child expansion: varRef=<lua-side reference>, stackId=0
     */
    fun sendGetVariablesRequest(varRef: Int, stackId: Int, callback: ((JsonObject) -> Unit)? = null) {
        val info = JsonObject().apply {
            // VSCode original sends varRef and stackId as numbers in JSON.
            // LuaPanda.lua uses tonumber() on both, which works for both types.
            // Send stackId as string to match VSCode's getWatchedVariable convention.
            addProperty("varRef", varRef)
            addProperty("stackId", stackId.toString())
        }
        sendCommand(ProtocolConstants.CMD_GET_VARIABLE, info, callback)
    }

    /**
     * Send a REPL expression to execute in the Lua runtime.
     */
    fun sendREPLExpression(expression: String, callback: ((JsonObject) -> Unit)? = null) {
        val info = JsonObject().apply {
            addProperty("expression", expression)
        }
        sendCommand(ProtocolConstants.CMD_RUN_REPL, info, callback)
    }

    /**
     * Called when a LuaPanda.lua client connects (server mode) or when we connect to it (client mode).
     *
     * Protocol handshake sequence (matching original VSCode LuaPanda):
     *   1. Send "initSuccess" — completes the handshake, Lua side unblocks
     *   2. Send "setBreakPoint" — synchronize all breakpoints
     *
     * Without sending initSuccess, LuaPanda.lua stays blocked waiting for the IDE to acknowledge
     * the connection, causing the Lua process to hang even if there are no breakpoints.
     */
    /** Tracks whether breakpoints have been sent after the handshake */
    @Volatile
    private var breakpointsSentAfterHandshake = false

    /**
     * Handle to the currently-armed handshake fallback task.
     *
     * CRITICAL (regression fix, 2026-04-23):
     * Without storing this handle, reconnects leaked scheduled tasks. The previous session's
     * fallback (armed with a 3s delay) would fire ~3s later during the NEW session's handshake
     * window, triggering `sendSetBreakpoints()` while Lua was still inside its initSuccess
     * handler — causing `SocketException: Connection reset` on every reconnect and producing
     * an infinite reconnect loop. See idea.log 17:47:44.405 timestamp for the smoking gun:
     * fallback logged "NOT received within 3000ms" only 26ms after readLoop started.
     *
     * We now cancel it in three places:
     *   1. Entry of `onDebuggerConnected()` — before arming a new one
     *   2. `handleInitSuccessReply()` — the normal success path, once reply arrives
     *   3. `onClientDisconnected` — so a dying session doesn't fire into the next
     */
    @Volatile
    private var handshakeFallbackFuture: java.util.concurrent.ScheduledFuture<*>? = null

    private fun onDebuggerConnected() {
        val startMs = System.currentTimeMillis()
        breakpointsSentAfterHandshake = false
        receivedInitSuccessReply = false
        // Cancel any leftover fallback from a previous (aborted) session.
        handshakeFallbackFuture?.cancel(false)
        handshakeFallbackFuture = null
        logger.info("onDebuggerConnected: starting handshake at $startMs, registeredBreakpoints=${registeredBreakpoints.size}")
        // Step 1: Complete protocol handshake — send initSuccess.
        //
        // LuaPanda.lua's initSuccess handler will:
        //   1. Process our config (autoPathMode, pathCaseSensitivity, etc.)
        //   2. Reply with its own initSuccess message (debugger version info)
        //   3. Call debugger_wait_msg(1) — wait up to 1s in WAIT_CMD for breakpoints
        //   4. Call changeRunState(RUN) — start executing user code
        //
        // We send breakpoints in handleInitSuccessReply() (triggered by step 2),
        // which happens during step 3's WAIT_CMD window. This ensures breakpoints
        // are set BEFORE Lua enters RUN state.
        sendInitSuccess()
        logger.info("onDebuggerConnected: initSuccess sent at ${System.currentTimeMillis()} (${System.currentTimeMillis() - startMs}ms elapsed)")

        // Safety net: if handleInitSuccessReply() doesn't fire within `handshakeFallbackMs`
        // (e.g. Lua reply was lost or malformed), send breakpoints anyway.
        //
        // This delay must be tuned to the network topology:
        //   * localhost loopback → reply typically arrives in <20ms, any delay >100ms is safe
        //   * remote LAN (physical / VM / device)
        //                         → reply can take 300~2000ms depending on Lua script-load time;
        //                           firing the fallback too early triggers a race where
        //                           `setBreakPoint` reaches Lua before initSuccess handler
        //                           finishes initialising internal state, causing
        //                           `SocketException: Connection reset` mid-handshake.
        //
        // Default is 3000ms (configurable via Run Configuration → "Handshake fallback (ms)").
        //
        // Additional guard: even after the timer fires, if we've received ANY message from
        // the peer within the last 500ms, the peer is alive-but-busy — skip the fallback and
        // defer to the real reply path. This prevents the fallback from ever racing against
        // a reply that's *about to arrive*.
        val fallbackMs = config.handshakeFallbackMs.toLong()
        handshakeFallbackFuture = bpSyncScheduler.schedule({
            try {
                if (!breakpointsSentAfterHandshake && connected && !stopping) {
                    val lastRx = processor?.lastRxTimestamp ?: 0L
                    val sinceLastRx = if (lastRx == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastRx
                    if (sinceLastRx < 500L) {
                        logger.info("onDebuggerConnected: fallback suppressed — peer active ${sinceLastRx}ms ago (reply likely in flight)")
                        return@schedule
                    }
                    logger.warn("onDebuggerConnected: initSuccess reply NOT received within ${fallbackMs}ms! Sending breakpoints as fallback.")
                    breakpointsSentAfterHandshake = true
                    sendSetBreakpoints()
                }
            } catch (e: Exception) {
                logger.warn("onDebuggerConnected: fallback breakpoint sync failed", e)
            }
        }, fallbackMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * First-level diagnostic hint: after 3 consecutive short-lived connections where Lua
     * never replied to initSuccess, warn the user about the most likely root cause —
     * Lua side `connectTimeoutSec` (default 0.005s = 5ms) being too small for their RTT.
     *
     * Why this is almost always the cause (vs. the generic "script reload" hint):
     *   - Lua client-mode start() uses `sock:settimeout(0.005)` then `sock:connect()`
     *   - For host != 127.0.0.1 (LAN / VPN / container) RTT is typically 30~200ms
     *   - connect() returns timeout → Lua abandons the socket WITHOUT calling receive()
     *   - Kernel still completes SYN-ACK → IDE's accept() succeeds, sends initSuccess
     *   - Nobody on Lua side reads it; ~1s later reConnect() closes the stale fd
     *   - Loop repeats until an RTT jitter happens to fit the 5ms window
     */
    private fun emitConnectTimeoutHint() {
        val isLocalhost = config.host == "127.0.0.1" || config.host.equals("localhost", ignoreCase = true)
        val hostClause = if (isLocalhost) {
            "host=${config.host}"
        } else {
            "host=${config.host} (non-localhost — network RTT likely > 5ms)"
        }
        printToConsole(
            "[LuaPanda] Detected 3 short-lived connections where Lua never replied to initSuccess.\n" +
            "  This pattern almost always means Lua-side `connectTimeoutSec` is too small for your network.\n" +
            "  Connection info: $hostClause, port=${config.port}\n" +
            "  Root cause: LuaPanda.lua default `connectTimeoutSec = 0.005` (5ms) — too tight for non-local hosts.\n" +
            "              Lua's sock:connect() times out before TCP handshake completes from Lua's view,\n" +
            "              so Lua never reads IDE's initSuccess command. The socket is then reset ~1s later.\n" +
            "  If this loop persists, see the next hint after 10 failures for a concrete patch.\n",
            ConsoleViewContentType.LOG_WARNING_OUTPUT
        )
        logger.warn("emitConnectTimeoutHint: 3 consecutive silent short-lived connections (host=${config.host}, port=${config.port})")
    }

    /**
     * Second-level (escalated) hint: after 10 silent short-lived connections, give the user
     * a concrete one-line patch they can apply to their Lua-side LuaPanda.lua.
     */
    private fun emitConnectTimeoutHintEscalated() {
        printToConsole(
            "[LuaPanda] ERROR: 10 consecutive failed handshakes — Lua never reads IDE's initSuccess.\n" +
            "  Almost certainly caused by `connectTimeoutSec = 0.005` in your LuaPanda.lua (line ~59).\n" +
            "  Suggested patch (applied on the LUA side, not the IDE plugin):\n" +
            "\n" +
            "    -- In your LuaPanda.lua, replace line ~266 inside function this.start():\n" +
            "    --   sock:settimeout(connectTimeoutSec);\n" +
            "    -- with:\n" +
            "      if host == \"127.0.0.1\" or host == \"localhost\" then\n" +
            "          sock:settimeout(connectTimeoutSec);      -- keep 5ms for localhost\n" +
            "      else\n" +
            "          sock:settimeout(0.5);                    -- allow 500ms for LAN/VPN\n" +
            "      end\n" +
            "\n" +
            "  This only affects the first connect(); attach-mode reConnect() keeps the original 5ms.\n" +
            "  (If you can confirm ~10,000 reconnect lines in the IDE log prior to this, that's the\n" +
            "   exact scenario this hint targets.)\n",
            ConsoleViewContentType.ERROR_OUTPUT
        )
        logger.error("emitConnectTimeoutHintEscalated: 10 consecutive silent short-lived connections — strongly suggest fixing Lua-side connectTimeoutSec (host=${config.host})")
    }



    /**
     * Send initSuccess command to complete the protocol handshake.
     *
     * This is the first command that must be sent after TCP connection is established.
     * LuaPanda.lua blocks in connectSuccess() -> debugger_wait_msg() waiting for this command.
     * The info payload configures the debugger behavior on the Lua side.
     *
     * Required fields (from LuaPanda.lua dataProcess "initSuccess" handler):
     *   - stopOnEntry: whether to pause at entry point
     *   - pathCaseSensitivity: path case sensitivity
     *   - autoPathMode: automatic path matching
     *   - logLevel: log verbosity (0=verbose/debug, 1=info, 2=error only)
     *   - useCHook: whether to use C hook library (false = pure Lua debug)
     *   - adapterVersion: version string for compatibility checks
     *   - cwd: workspace root path (used for path resolution on Lua side)
     *   - luaFileExtension: Lua file extension (e.g. "" or ".lua.txt")
     *   - TempFilePath: temp file path (used by Lua side for internal file ops)
     *   - OSType: operating system type ("Windows_NT", "Darwin", "Linux")
     *   - isNeedB64EncodeStr: whether to base64-encode strings
     *   - distinguishSameNameFile: whether to distinguish files with same name
     *   - truncatedOPath: path truncation flag
     *   - clibPath: path to C debug library
     */
    private fun sendInitSuccess() {
        try {
            val projectPath = session.project.basePath ?: ""
            logger.info("sendInitSuccess: projectPath='$projectPath', stopOnEntry=${config.stopOnEntry}")
            val info = JsonObject().apply {
                // LuaPanda.lua compares some fields with string "true"/"false",
                // so we send booleans as strings to match the original VSCode behavior.
                addProperty("stopOnEntry", config.stopOnEntry.toString())
                // pathCaseSensitivity: LuaPanda.lua's genUnifiedPath uses this to decide
                // whether to call string.lower() on paths. On Windows, paths are case-insensitive,
                // so we MUST send "false" — this makes genUnifiedPath lowercase all paths,
                // ensuring our breakpoint paths (also lowercased by PathResolver) match
                // the runtime paths from debug.getinfo().
                // If we send "true" on Windows, genUnifiedPath won't lowercase and the
                // breakpoint paths (lowercased) will never match runtime paths (mixed case),
                // causing all breakpoints to silently fail.
                addProperty("pathCaseSensitivity", (!isWindows).toString())
                addProperty("autoPathMode", true.toString())
                addProperty("logLevel", 2)  // 0=verbose/debug, 1=info, 2=error only (default: error only to minimize performance overhead)
                // useCHook: controlled by Run Configuration checkbox. Default true (high-performance C hook;
                // plugin bundles precompiled libpdebug for Lua 5.1/5.3/5.4 on Win x86/x64 and Mac x64/arm64).
                // Users whose Lua runtime doesn't match (LuaJIT, Lua 5.2, remote debugging to a machine without
                // the bundled libs, unusual builds) should uncheck it; LuaPanda.lua will then fall back to pure
                // Lua debug (slower but compatible with any runtime).
                addProperty("useCHook", config.useCHook.toString())
                addProperty("adapterVersion", "3.2.0")          // Compatible with LuaPanda 3.x protocol
                addProperty("cwd", projectPath)
                addProperty("luaFileExtension", "")              // Default: standard .lua files
                addProperty("TempFilePath", "")
                addProperty("OSType", getOSType())
                addProperty("isNeedB64EncodeStr", false.toString())
                addProperty("distinguishSameNameFile", false.toString())
                addProperty("truncatedOPath", "")
                // clibPath is only meaningful when useCHook is enabled.
                // Priority when enabled: user-configured > bundled (auto-extracted) > empty (pure Lua fallback).
                // When disabled, send empty string so LuaPanda.lua won't attempt to load any C library.
                val effectiveClibPath = if (config.useCHook) {
                    config.clibPath.ifEmpty { extractBundledClibPath() ?: "" }
                } else {
                    ""
                }
                addProperty("clibPath", effectiveClibPath)
                if (config.useCHook && effectiveClibPath.isNotEmpty()) {
                    logger.info("sendInitSuccess: clibPath='$effectiveClibPath' (${if (config.clibPath.isNotEmpty()) "user-configured" else "bundled"})")
                } else if (!config.useCHook) {
                    logger.info("sendInitSuccess: C hook disabled by configuration — using pure Lua debug")
                }
            }
            logger.info("sendInitSuccess: info JSON constructed, sending command")
            sendCommand(ProtocolConstants.CMD_INIT_SUCCESS, info)
            printToConsole("Sent initSuccess to LuaPanda\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            logger.info("sendInitSuccess: command sent successfully")
        } catch (e: Exception) {
            logger.error("sendInitSuccess: EXCEPTION during initSuccess construction/send!", e)
            printToConsole("ERROR sending initSuccess: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
        }
    }

    /**
     * Detect the current OS type in the format expected by LuaPanda.lua.
     * LuaPanda.lua uses: "Windows_NT", "Darwin", "Linux"
     */
    private fun getOSType(): String {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            osName.contains("win") -> "Windows_NT"
            osName.contains("mac") || osName.contains("darwin") -> "Darwin"
            else -> "Linux"
        }
    }

    // ==================== Bundled libpdebug Extraction ====================

    /**
     * Extract the bundled libpdebug C hook libraries from plugin resources to a local directory.
     *
     * LuaPanda.lua's clibPath loading mechanism (when isUserSetClibPath == false) constructs
     * search paths as: `clibPath + platform + "/" + arch + "/" + lua_ver + "/?.ext;"`
     * For example: `<clibPath>win/x86_64/503/?.dll;`
     *
     * So we need to extract our bundled libraries maintaining this exact structure:
     * ```
     * <extractDir>/
     * ├── win/
     * │   ├── x86/
     * │   │   ├── 501/libpdebug.dll
     * │   │   └── 503/libpdebug.dll
     * │   └── x86_64/
     * │       ├── 501/libpdebug.dll
     * │       ├── 503/libpdebug.dll
     * │       └── 504/libpdebug.dll
     * └── mac/
     *     ├── arm_64/
     *     │   ├── 503/libpdebug.so
     *     │   └── 504/libpdebug.so
     *     └── x86_64/
     *         ├── 501/libpdebug.so
     *         └── 503/libpdebug.so
     * ```
     *
     * The extracted directory is cached and reused across sessions.
     *
     * @return The path to the extraction root directory (to be used as clibPath),
     *         with a trailing "/" so LuaPanda.lua can directly concatenate platform/arch/ver.
     *         Returns null if extraction fails.
     */
    private fun extractBundledClibPath(): String? {
        try {
            // Use a stable directory under the system temp dir, keyed by plugin version
            // so re-extraction happens on plugin update (old version's cache won't be reused)
            val pluginVersion = com.intellij.ide.plugins.PluginManagerCore
                .getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.luapanda.idea"))
                ?.version ?: "unknown"
            val extractDir = java.io.File(System.getProperty("java.io.tmpdir"), "luapanda-idea-clib-$pluginVersion")

            // Marker file to avoid re-extracting on every debug session
            val markerFile = java.io.File(extractDir, ".extracted")
            if (markerFile.exists()) {
                val clibPath = extractDir.absolutePath.replace('\\', '/') + "/"
                logger.info("extractBundledClibPath: using cached extraction at $clibPath")
                return clibPath
            }

            // Define all bundled library paths (relative to resources/debugger/debugger_lib/plugins/)
            val libraryPaths = listOf(
                "win/x86/501/libpdebug.dll",
                "win/x86/503/libpdebug.dll",
                "win/x86_64/501/libpdebug.dll",
                "win/x86_64/503/libpdebug.dll",
                "win/x86_64/504/libpdebug.dll",
                "mac/arm_64/503/libpdebug.so",
                "mac/arm_64/504/libpdebug.so",
                "mac/x86_64/501/libpdebug.so",
                "mac/x86_64/503/libpdebug.so"
            )

            logger.info("extractBundledClibPath: extracting bundled libpdebug libraries to $extractDir")

            var extractedCount = 0
            for (relativePath in libraryPaths) {
                val resourcePath = "/debugger/debugger_lib/plugins/$relativePath"
                val inputStream = javaClass.getResourceAsStream(resourcePath)
                if (inputStream == null) {
                    logger.debug("extractBundledClibPath: resource not found: $resourcePath (skipping)")
                    continue
                }

                val targetFile = java.io.File(extractDir, relativePath)
                targetFile.parentFile.mkdirs()

                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                extractedCount++
                logger.debug("extractBundledClibPath: extracted $relativePath (${targetFile.length()} bytes)")
            }

            if (extractedCount == 0) {
                logger.warn("extractBundledClibPath: no libraries were extracted!")
                return null
            }

            // Create marker file
            markerFile.createNewFile()

            val clibPath = extractDir.absolutePath.replace('\\', '/') + "/"
            logger.info("extractBundledClibPath: successfully extracted $extractedCount libraries to $clibPath")
            return clibPath

        } catch (e: Exception) {
            logger.warn("extractBundledClibPath: extraction failed", e)
            return null
        }
    }

    // ==================== Utility ====================

    fun getPathResolver(): PathResolver = pathResolver

    /** Whether a LuaPanda.lua peer is currently connected (requests can be answered). */
    fun isConnected(): Boolean = connected && processor != null

    /**
     * Adds the "Search Lua Variables" button to the Debug tool window toolbar
     * (the action itself is declared in plugin.xml).
     */
    override fun registerAdditionalActions(
        leftToolbar: DefaultActionGroup,
        topToolbar: DefaultActionGroup,
        settings: DefaultActionGroup
    ) {
        super.registerAdditionalActions(leftToolbar, topToolbar, settings)
        ActionManager.getInstance().getAction(SearchLuaVariablesAction.ACTION_ID)?.let { topToolbar.add(it) }
    }

    /**
     * Print a message to the debug console.
     *
     * Uses our own [myConsole] reference (set in [createConsole]) instead of
     * `session.consoleView`, which may return null during early initialization.
     *
     * ConsoleView.print() is thread-safe — no invokeLater needed.
     */
    private fun printToConsole(message: String, type: ConsoleViewContentType) {
        // Log to IDEA log file at debug level only (avoids doubling I/O for every console message)
        if (logger.isDebugEnabled) {
            logger.debug("[Console] $message")
        }

        try {
            if (::myConsole.isInitialized) {
                myConsole.print(message, type)
            } else {
                logger.warn("[Console not initialized] $message")
            }
        } catch (e: Exception) {
            logger.warn("[Console error] Failed to print: $message", e)
        }
    }
}
