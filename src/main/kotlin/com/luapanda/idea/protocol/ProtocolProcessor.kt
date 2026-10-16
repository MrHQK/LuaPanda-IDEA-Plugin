package com.luapanda.idea.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles the LuaPanda TCP/JSON protocol framing, serialization and command dispatch.
 *
 * LuaPanda message frame format (from dataProcessor.ts / LuaPanda.lua):
 *   [JSON_STRING] + "|*|" + "\n"
 *
 * The JSON body uses this structure:
 *   { "cmd": "<command>", "callbackId": "<id>", "info": { ... } }
 *
 * Messages are delimited by the TCP split character "|*|" followed by newline.
 * The receiving end reads lines and splits by "|*|" to extract individual messages.
 */
class ProtocolProcessor(
    private val socket: Socket,
    private val listener: ProtocolListener
) {
    private val logger = Logger.getInstance(ProtocolProcessor::class.java)

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** Pending callback map: callbackId -> callback function */
    private val pendingCallbacks = ConcurrentHashMap<String, CallbackEntry>()
    private val callbackIdGenerator = AtomicLong(1)

    /** Callback timeout in milliseconds */
    private val callbackTimeoutMs = 10_000L

    /** Periodic callback cleanup scheduler — runs every 5 seconds instead of on every message */
    private val callbackCleanupScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LuaPanda-Callback-Cleanup").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    /**
     * Timestamp (ms, System.currentTimeMillis) of the most recently received message.
     * 0 until the first message is processed. Used by handshake fallback logic to
     * detect "peer is alive but busy" scenarios and avoid firing the safety net
     * prematurely — especially important on non-loopback (remote) connections
     * where RTT can exceed the default safety-net threshold.
     */
    @Volatile
    var lastRxTimestamp: Long = 0L
        private set

    data class CallbackEntry(
        val callback: (JsonObject) -> Unit,
        val createdAt: Long = System.currentTimeMillis()
    )

    interface ProtocolListener {
        fun onMessage(message: JsonObject)
        /**
         * Invoked when the TCP connection is closed.
         * @param reason a short human-readable reason (EOF, SocketException:Connection reset, IOException:..., etc.),
         *               useful for diagnosing frequent-reconnect issues.
         * @param connectionDurationMs how long the connection was alive before closing (milliseconds).
         *                             Short durations (<1s) after a successful handshake typically indicate
         *                             the Lua host actively reopened the connection (e.g. script reload).
         */
        fun onDisconnected(reason: String = "unknown", connectionDurationMs: Long = 0L)
        fun onError(error: Exception)
    }

    /**
     * Initialize the I/O streams so that sendMessage() / sendCommand() can be used
     * **before** entering the blocking readLoop.
     *
     * Call sequence:
     *   1. initStreams()          — sets up reader/writer (safe to send messages after this)
     *   2. <send initSuccess etc> — the caller can now send handshake commands
     *   3. startReadLoop()        — enters the blocking read loop
     */
    fun initStreams() {
        // Disable Nagle's algorithm so that small messages (like initSuccess handshake)
        // are sent immediately without waiting for ACK or more data.
        // This is critical for the handshake: LuaPanda.lua blocks in debugger_wait_msg()
        // waiting for initSuccess, and Nagle delay could cause a noticeable pause.
        try {
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            logger.warn("Failed to set TCP_NODELAY", e)
        }
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        reader = BufferedReader(InputStreamReader(inputStream!!, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(outputStream!!, Charsets.UTF_8))
        running = true
        logger.info("initStreams: TCP_NODELAY=${socket.tcpNoDelay}, socket=${socket.inetAddress}:${socket.port}")
    }

    /**
     * Enter the blocking read loop.  Call initStreams() first.
     * This method blocks the current thread until the connection is closed.
     */
    fun startReadLoop() {
        try {
            readLoop()
        } catch (e: Exception) {
            logger.warn("ProtocolProcessor readLoop error", e)
            listener.onError(e)
        }
    }

    /**
     * Convenience method that initialises streams and immediately enters the read loop.
     * Kept for backward compatibility — but callers that need to send a handshake
     * command before reading should use initStreams() + startReadLoop() separately.
     */
    fun start() {
        try {
            initStreams()
            readLoop()
        } catch (e: Exception) {
            logger.warn("ProtocolProcessor start error", e)
            listener.onError(e)
        }
    }

    fun stop() {
        running = false
        pendingCallbacks.clear()
        callbackCleanupScheduler.shutdownNow()
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Send a message to the connected LuaPanda.lua client.
     * Frame format: JSON + "|*|" + "\n"
     */
    fun sendMessage(json: JsonObject) {
        try {
            val body = json.toString()
            // LuaPanda.lua sends: sendStr .. TCPSplitChar .. "\n"  (NO space before delimiter)
            // We must match the exact same format: JSON + "|*|" + "\n"
            val frame = "$body${ProtocolConstants.TCP_SPLIT_CHAR}\n"
            val cmd = json.get("cmd")?.asString ?: "?"
            if (logger.isDebugEnabled) {
                logger.debug(">>> Preparing to send cmd=$cmd, frameLen=${frame.length}, socketClosed=${socket.isClosed}, socketConnected=${socket.isConnected}")
            }
            synchronized(this) {
                val w = writer
                if (w == null) {
                    logger.error("Cannot send message: writer not initialized (call initStreams() first). cmd=$cmd")
                    return
                }
                w.write(frame)
                w.flush()
                // BufferedWriter.flush() already flushes to the underlying stream,
                // no need for a redundant outputStream.flush() which doubles the syscall cost.
            }
            if (logger.isDebugEnabled) {
                val preview = if (body.length > 300) body.substring(0, 300) + "..." else body
                logger.debug(">>> Sent cmd=$cmd, bodyLen=${body.length}, preview=$preview")
            }
        } catch (e: Exception) {
            logger.warn("Send message error (cmd=${json.get("cmd")?.asString ?: "?"})", e)
            listener.onError(e)
        }
    }

    /**
     * Send a command message to LuaPanda.lua.
     *
     * @param cmd The command string (e.g. "continue", "stopOnStep", "setBreakPoint")
     * @param info Optional data payload
     * @param callback Optional callback for request-response matching via callbackId
     */
    fun sendCommand(cmd: String, info: JsonObject? = null, callback: ((JsonObject) -> Unit)? = null) {
        val msg = JsonObject().apply {
            addProperty("cmd", cmd)
            if (callback != null) {
                val cbId = callbackIdGenerator.getAndIncrement().toString()
                addProperty("callbackId", cbId)
                pendingCallbacks[cbId] = CallbackEntry(callback)
            } else {
                addProperty("callbackId", "0")
            }
            // LuaPanda.lua's dataProcess() always reads the "info" field from the message.
            // If "info" is missing, the local variable is nil, causing:
            //   "attempt to index a nil value (local 'info')"
            // We MUST always include an "info" field, even if it's an empty object.
            add("info", info ?: JsonObject())
        }
        sendMessage(msg)
    }

    /**
     * Send multiple messages in a single batch, flushing only once.
     * This is more efficient than calling sendMessage() N times, which
     * would produce N flush() syscalls. Useful for bulk breakpoint sync.
     */
    fun sendMessages(messages: List<JsonObject>) {
        if (messages.isEmpty()) return
        try {
            synchronized(this) {
                val w = writer
                if (w == null) {
                    logger.error("Cannot send batch messages: writer not initialized")
                    return
                }
                for (json in messages) {
                    val body = json.toString()
                    val frame = "$body${ProtocolConstants.TCP_SPLIT_CHAR}\n"
                    w.write(frame)
                    if (logger.isDebugEnabled) {
                        val cmd = json.get("cmd")?.asString ?: "?"
                        logger.debug(">>> Batched cmd=$cmd, bodyLen=${body.length}")
                    }
                }
                w.flush()
            }
            if (logger.isDebugEnabled) {
                logger.debug(">>> Flushed batch of ${messages.size} messages")
            }
        } catch (e: Exception) {
            logger.warn("Batch send error", e)
            listener.onError(e)
        }
    }

    /**
     * Send a JSON string directly with framing.
     */
    fun sendRaw(jsonString: String) {
        try {
            val frame = "$jsonString${ProtocolConstants.TCP_SPLIT_CHAR}\n"
            synchronized(this) {
                writer?.write(frame)
                writer?.flush()
            }
            logger.info(">>> Sent raw, length=${jsonString.length}")
        } catch (e: Exception) {
            logger.warn("Send raw message error", e)
            listener.onError(e)
        }
    }

    // ==================== Internal ====================

    /**
     * Read loop that processes incoming LuaPanda protocol messages.
     *
     * LuaPanda.lua sends messages as: JSON + " |*|\n"
     * We read lines and split by "|*|" to handle potential message concatenation (sticky packets).
     */
    private fun readLoop() {
        val recvMsgQueue = ArrayDeque<String>()
        var cutoffString = ""
        val connectionStartMs = System.currentTimeMillis()
        // Disconnect reason, filled by the catch blocks or left as EOF if readLine returned null.
        var disconnectReason = "EOF (remote closed cleanly — readLine returned null)"

        try {
            val localReader = reader ?: return
            val splitChar = ProtocolConstants.TCP_SPLIT_CHAR
            val splitCharLen = splitChar.length
            logger.info("readLoop: started, waiting for messages from LuaPanda.lua")
            while (running && !socket.isClosed) {
                val line = localReader.readLine()
                if (line == null) {
                    // readLine returning null means the remote side closed the TCP stream cleanly (FIN).
                    // This is the most common reason for frequent reconnects: the Lua host ran
                    // LuaPanda.stop() or re-required LuaPanda (e.g. script hot-reload).
                    disconnectReason = "EOF (remote FIN — Lua side closed socket, likely stop()/reload)"
                    break
                }
                if (logger.isDebugEnabled) {
                    logger.debug("readLoop: received line, length=${line.length}")
                }

                // Combine with any previous partial data
                val rawData = cutoffString + line
                cutoffString = ""

                // Split by TCP split character using indexOf+substring to avoid
                // creating intermediate List<String> (reduces GC pressure for high-frequency messages)
                var searchFrom = 0
                var foundDelimiter = false
                while (true) {
                    val idx = rawData.indexOf(splitChar, searchFrom)
                    if (idx < 0) break
                    foundDelimiter = true
                    val msgStr = rawData.substring(searchFrom, idx).trim()
                    if (msgStr.isNotEmpty()) {
                        recvMsgQueue.add(msgStr)
                    }
                    searchFrom = idx + splitCharLen
                }

                if (!foundDelimiter) {
                    // No delimiter found - accumulate as partial data
                    cutoffString = rawData
                    continue
                }

                // The remaining part after the last delimiter might be a partial message
                if (searchFrom < rawData.length) {
                    val lastPart = rawData.substring(searchFrom).trim()
                    if (lastPart.isNotEmpty()) {
                        cutoffString = lastPart
                    }
                }

                // Process queued messages — ArrayDeque.poll() is O(1) vs ArrayList.removeAt(0) O(n)
                while (true) {
                    val msgStr = recvMsgQueue.poll() ?: break
                    processMessage(msgStr)
                }
            }
            // Loop exited because running==false or socket.isClosed — likely local stop().
            if (!running) {
                disconnectReason = "local stop (running flag cleared by IDE side)"
            } else if (socket.isClosed) {
                disconnectReason = "socket closed locally (socket.isClosed==true)"
            }
        } catch (e: java.net.SocketException) {
            // Typical messages: "Connection reset", "Socket closed", "Software caused connection abort"
            disconnectReason = "SocketException: ${e.message ?: "unknown"}"
            if (running) {
                logger.info("readLoop: $disconnectReason")
            }
        } catch (e: java.net.SocketTimeoutException) {
            disconnectReason = "SocketTimeoutException: ${e.message ?: "read timed out"}"
            if (running) {
                logger.info("readLoop: $disconnectReason")
            }
        } catch (e: IOException) {
            disconnectReason = "IOException: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            if (running) {
                logger.info("readLoop: $disconnectReason")
            }
        } catch (e: Exception) {
            disconnectReason = "Unexpected ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            if (running) {
                logger.warn("readLoop: $disconnectReason", e)
                listener.onError(e)
            }
        } finally {
            running = false
            val durationMs = System.currentTimeMillis() - connectionStartMs
            logger.info("readLoop: exiting — reason=[$disconnectReason], duration=${durationMs}ms")
            listener.onDisconnected(disconnectReason, durationMs)
        }
    }

    /**
     * Parse and dispatch a single JSON message.
     */
    private fun processMessage(msgStr: String) {
        // Update RX timestamp BEFORE any parsing/dispatch — even malformed messages
        // are evidence that the peer is alive, which is exactly what handshake fallback
        // logic needs to know.
        lastRxTimestamp = System.currentTimeMillis()
        if (logger.isDebugEnabled) {
            logger.debug("<<< Received message, length=${msgStr.length}")
            val preview = if (msgStr.length > 500) msgStr.substring(0, 500) + "..." else msgStr
            logger.debug("<<< Message preview: $preview")
        }
        try {
            val json = JsonParser.parseString(msgStr).asJsonObject
            if (logger.isDebugEnabled) {
                logger.debug("<<< Parsed cmd=${json.get("cmd")?.asString ?: "?"}")
            }

            // Check if this is a callback response
            val callbackId = json.get("callbackId")?.asString
            if (callbackId != null && callbackId != "0") {
                val entry = pendingCallbacks.remove(callbackId)
                if (entry != null) {
                    entry.callback(json)
                    return
                }
            }

            // Regular message - dispatch to listener
            listener.onMessage(json)
        } catch (e: Exception) {
            logger.warn("Failed to parse message: $msgStr", e)
        }
    }

    /**
     * Remove callbacks that have exceeded their timeout.
     */
    private fun cleanupTimedOutCallbacks() {
        val now = System.currentTimeMillis()
        val expired = pendingCallbacks.entries.filter { now - it.value.createdAt > callbackTimeoutMs }
        for (entry in expired) {
            pendingCallbacks.remove(entry.key)
            logger.warn("Callback ${entry.key} timed out")
        }
    }
}
