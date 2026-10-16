package com.luapanda.idea.protocol

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP Client for reverse connection mode.
 *
 * In reverse mode, the IDE acts as a client, connecting to a Lua process
 * that is acting as a server (useful for real device debugging).
 */
class LuaPandaClient(
    private val host: String,
    private val port: Int,
    private val listener: ClientListener
) {
    private val logger = Logger.getInstance(LuaPandaClient::class.java)
    private var socket: Socket? = null
    private var processor: ProtocolProcessor? = null
    private val isRunning = AtomicBoolean(false)

    interface ClientListener {
        fun onConnected(processor: ProtocolProcessor)
        /**
         * @param reason human-readable disconnect reason (EOF / SocketException:... / etc.)
         * @param connectionDurationMs connection lifetime in milliseconds
         */
        fun onDisconnected(reason: String = "unknown", connectionDurationMs: Long = 0L)
        fun onMessage(message: JsonObject)
        fun onError(error: Exception)
    }

    /**
     * Connect to the remote Lua server.
     * This method blocks and should be called from a background thread.
     */
    fun connect() {
        try {
            logger.info("Connecting to LuaPanda at $host:$port...")
            socket = Socket(host, port)
            isRunning.set(true)
            logger.info("Connected to LuaPanda at $host:$port")

            val protocolListener = object : ProtocolProcessor.ProtocolListener {
                override fun onMessage(message: JsonObject) {
                    listener.onMessage(message)
                }

                override fun onDisconnected(reason: String, connectionDurationMs: Long) {
                    isRunning.set(false)
                    listener.onDisconnected(reason, connectionDurationMs)
                }

                override fun onError(error: Exception) {
                    listener.onError(error)
                }
            }

            processor = ProtocolProcessor(socket!!, protocolListener)
            // Initialise I/O streams first so that the callback can safely send messages
            // (e.g. initSuccess handshake) before we enter the blocking read loop.
            processor!!.initStreams()
            logger.info("Client: streams initialized, calling onConnected callback")

            try {
                listener.onConnected(processor!!)
                logger.info("Client: onConnected callback completed successfully")
            } catch (e: Exception) {
                logger.error("Client: onConnected callback threw an exception!", e)
            }

            logger.info("Client: entering read loop")
            processor!!.startReadLoop() // Blocks until connection closes
            logger.info("Client: read loop exited")
        } catch (e: Exception) {
            logger.warn("Connection failed: ${e.message}")
            listener.onError(e)
        }
    }

    /**
     * Disconnect from the Lua server.
     */
    fun disconnect() {
        isRunning.set(false)
        try {
            processor?.stop()
            socket?.close()
        } catch (e: Exception) {
            logger.debug("Error during client disconnect", e)
        }
    }

    /**
     * Get the active protocol processor for sending messages.
     */
    fun getProcessor(): ProtocolProcessor? = processor

    fun isConnected(): Boolean = isRunning.get() && socket?.isConnected == true
}
