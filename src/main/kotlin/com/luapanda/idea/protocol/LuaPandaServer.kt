package com.luapanda.idea.protocol

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP Server that listens for connections from LuaPanda.lua clients.
 *
 * Standard mode: IDE acts as the server, Lua client connects.
 *
 * ## Concurrent-accept design (aligned with VSCode extension)
 *
 * VSCode LuaPanda extension keeps the TCP server listening *until the Lua side replies
 * `initSuccess`*. Only then does it call `_server.close()` to stop accepting new
 * connections (see Tencent/LuaPanda `luaDebug.ts`). This transparently handles the
 * "zombie socket" problem caused by Lua's default `connectTimeoutSec = 0.005` (5ms):
 * when Lua's `sock:connect()` times out on a non-local host, it abandons the socket,
 * but the kernel SYN-ACK still completes so the IDE accepts a "zombie". Since the
 * server keeps listening, Lua's attach-mode `reConnect()` will eventually land a
 * live connection that is read normally — the zombie is silently replaced.
 *
 * Previous IDEA behaviour: serial `accept()` → blocking `handleClient()` → readLoop.
 * A zombie socket kept the server stuck in `handleClient()` until `Connection reset`
 * from Lua's fd closure, which then triggered the next accept — looping thousands
 * of times on bad networks.
 *
 * New behaviour:
 *   - `start()` loops on `accept()` indefinitely until [stop] is called.
 *   - Each accepted socket is handed to a dedicated handler thread.
 *   - A new connection arriving BEFORE the current one has completed the handshake
 *     silently replaces the previous zombie socket (close + drop).
 *   - Once the protocol layer confirms handshake completion, [markHandshakeComplete]
 *     freezes the active socket: subsequent accepted sockets are immediately closed.
 *   - No "connected / disconnected" noise is emitted for zombie sockets; only a
 *     single `onClientConnected` is delivered per completed handshake.
 */
class LuaPandaServer(
    private val port: Int,
    private val listener: ServerListener
) {
    private val logger = Logger.getInstance(LuaPandaServer::class.java)
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    /** Set to true once the caller (protocol layer) confirms handshake success.
     *  While false: newly accepted sockets REPLACE any previous candidate.
     *  Once true: newly accepted sockets are closed immediately. */
    private val handshakeComplete = AtomicBoolean(false)

    /** The currently "active" (most recently accepted, pre-handshake) processor.
     *  Replaced each time a fresh connection arrives before handshake completes. */
    @Volatile
    private var activeProcessor: ProtocolProcessor? = null

    /** The socket tied to `activeProcessor`. Kept separately so we can close it
     *  even if the processor was never fully started. */
    @Volatile
    private var activeSocket: Socket? = null

    /** Tracks every live handler thread so we can interrupt/clean them on stop(). */
    private val handlerThreads = ConcurrentHashMap.newKeySet<Thread>()

    interface ServerListener {
        /**
         * Invoked once, as soon as a socket is accepted and its streams are initialised.
         * The consumer uses this to send the initial protocol message (initSuccess).
         *
         * IMPORTANT: the consumer MUST NOT print user-visible "connected" messages here,
         * because this candidate may turn out to be a zombie and be silently replaced.
         * Emit user-visible success only after the handshake reply arrives, then call
         * [markHandshakeComplete] to freeze the active socket.
         */
        fun onClientConnected(processor: ProtocolProcessor)

        /**
         * Invoked when a FULLY-ESTABLISHED connection (post-handshake) drops.
         *
         * Zombie sockets that are replaced before handshake completion do NOT trigger
         * this callback — they are silently discarded. Users should not be notified
         * about momentary reconnect churn caused by Lua's `connectTimeoutSec`.
         *
         * @param reason human-readable disconnect reason (EOF / SocketException:... / etc.)
         * @param connectionDurationMs connection lifetime in milliseconds
         */
        fun onClientDisconnected(reason: String = "unknown", connectionDurationMs: Long = 0L)
        fun onMessage(message: JsonObject)
        fun onError(error: Exception)
        fun onServerStarted(port: Int)
    }

    /**
     * Start the TCP server on the specified port.
     * This method blocks and should be called from a background thread.
     */
    fun start() {
        try {
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            logger.info("LuaPanda server started on port $port (concurrent-accept mode)")
            listener.onServerStarted(port)

            while (isRunning.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    // Spawn a dedicated handler thread: accept loop MUST NOT block on readLoop.
                    // Each accepted socket races to become the "active" processor; earlier
                    // candidates get silently replaced (zombie scenario).
                    val t = Thread({ handleClient(socket) }, "LuaPanda-Client-${socket.inetAddress}:${socket.port}").apply {
                        isDaemon = true
                    }
                    handlerThreads.add(t)
                    t.start()
                } catch (e: SocketException) {
                    if (isRunning.get()) {
                        logger.warn("Server socket error", e)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                logger.error("Server start error", e)
                listener.onError(e)
            }
        }
    }

    /**
     * Stop the server and close all connections.
     */
    fun stop() {
        isRunning.set(false)
        try {
            activeProcessor?.stop()
        } catch (_: Exception) {}
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger.debug("Error closing server socket", e)
        }
        // Best-effort wake-up of handler threads still in readLoop.
        handlerThreads.forEach { t -> try { t.interrupt() } catch (_: Exception) {} }
        handlerThreads.clear()
        logger.info("LuaPanda server stopped")
    }

    /**
     * Get the active protocol processor for sending messages.
     */
    fun getProcessor(): ProtocolProcessor? = activeProcessor

    /**
     * Check if a client is currently connected.
     */
    fun isClientConnected(): Boolean {
        val s = activeSocket ?: return false
        return s.isConnected && !s.isClosed
    }

    /**
     * Called by the protocol layer once the handshake reply (initSuccess from Lua) arrives.
     *
     * After this point:
     *   - The active socket is "frozen"; no further replacements are allowed.
     *   - Newly accepted sockets are closed immediately (they would be duplicate
     *     Lua start() calls or stale retries).
     *   - The accept loop keeps running so it can survive a post-handshake drop
     *     (existing behaviour for `autoReconnect`).
     */
    fun markHandshakeComplete() {
        if (handshakeComplete.compareAndSet(false, true)) {
            logger.info("markHandshakeComplete: active socket frozen; further connections will be refused")
        }
    }

    /**
     * Resets the handshake-complete flag. Used when the caller intends to accept a
     * fresh connection after a previous session dropped (auto-reconnect mode).
     */
    fun resetHandshakeState() {
        handshakeComplete.set(false)
    }

    private fun handleClient(socket: Socket) {
        if (!isRunning.get()) {
            try { socket.close() } catch (_: Exception) {}
            handlerThreads.remove(Thread.currentThread())
            return
        }

        // If handshake already completed on a PREVIOUS socket, refuse this one.
        // This is the "VSCode-equivalent" of `_server.close()` — we keep listen()
        // active for autoReconnect but silently drop any connection arriving while
        // a live, authenticated session is in progress.
        if (handshakeComplete.get()) {
            logger.info("handleClient: handshake already complete — closing extra connection from ${socket.inetAddress}:${socket.port}")
            try { socket.close() } catch (_: Exception) {}
            handlerThreads.remove(Thread.currentThread())
            return
        }

        // Take over the "active" slot, displacing any previous zombie candidate.
        // The displaced processor's readLoop will exit on its own when the socket closes.
        val previousProcessor = activeProcessor
        val previousSocket = activeSocket
        if (previousProcessor != null || previousSocket != null) {
            logger.info("handleClient: replacing previous pre-handshake candidate (likely zombie) with ${socket.inetAddress}:${socket.port}")
            try { previousProcessor?.stop() } catch (_: Exception) {}
            try { previousSocket?.close() } catch (_: Exception) {}
        }

        activeSocket = socket
        logger.info("LuaPanda client accepted from ${socket.inetAddress}:${socket.port} (pre-handshake)")

        val protocolListener = object : ProtocolProcessor.ProtocolListener {
            override fun onMessage(message: JsonObject) {
                listener.onMessage(message)
            }

            override fun onDisconnected(reason: String, connectionDurationMs: Long) {
                // Only propagate the disconnect event if THIS connection was the one
                // that completed the handshake. Zombie sockets replaced pre-handshake
                // must remain invisible to the user.
                val wasActive = (activeSocket === socket)
                val handshakeDone = handshakeComplete.get()
                if (wasActive && handshakeDone) {
                    listener.onClientDisconnected(reason, connectionDurationMs)
                } else {
                    logger.info("handleClient: silent drop of pre-handshake connection (${socket.inetAddress}:${socket.port}) — reason=$reason, duration=${connectionDurationMs}ms")
                }
            }

            override fun onError(error: Exception) {
                // Only surface errors for the authenticated session — pre-handshake
                // errors are almost always the `connectTimeoutSec` churn noise.
                if (activeSocket === socket && handshakeComplete.get()) {
                    listener.onError(error)
                } else {
                    logger.debug("handleClient: suppressed pre-handshake error", error)
                }
            }
        }

        val thisProcessor = ProtocolProcessor(socket, protocolListener)
        activeProcessor = thisProcessor

        // Initialise I/O streams first so that the consumer callback can safely send
        // messages (e.g. initSuccess handshake) before we enter the blocking read loop.
        thisProcessor.initStreams()
        logger.info("handleClient: streams initialized, invoking onClientConnected callback")

        try {
            listener.onClientConnected(thisProcessor)
            logger.info("handleClient: onClientConnected callback completed successfully")
        } catch (e: Exception) {
            // CRITICAL: if the callback throws, we MUST still enter the read loop
            // so that the connection is properly managed and cleaned up.
            logger.error("handleClient: onClientConnected callback threw an exception!", e)
        }

        logger.info("handleClient: entering read loop")
        thisProcessor.startReadLoop() // Blocks until the connection is closed
        logger.info("handleClient: read loop exited for ${socket.inetAddress}:${socket.port}")

        // Clear active slot only if we are still the active processor; if a later
        // connection displaced us, the new handler already owns the slot.
        if (activeProcessor === thisProcessor) {
            activeProcessor = null
        }
        if (activeSocket === socket) {
            activeSocket = null
        }
        handlerThreads.remove(Thread.currentThread())
    }
}
