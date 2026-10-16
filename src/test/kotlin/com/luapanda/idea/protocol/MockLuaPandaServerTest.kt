package com.luapanda.idea.protocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end test using a mock LuaPanda server.
 *
 * Simulates the full TCP/JSON protocol exchange between the IDE (ProtocolProcessor)
 * and a mock Lua client, verifying:
 * - Connection handshake
 * - Message framing (JSON |*|\n)
 * - Command sending (IDE → Lua)
 * - Event receiving (Lua → IDE)
 * - callbackId request-response matching
 * - Sticky packet handling
 * - Disconnection handling
 */
class MockLuaPandaServerTest {

    private val gson = Gson()
    private val splitChar = ProtocolConstants.TCP_SPLIT_CHAR

    companion object {
        private const val TEST_PORT_BASE = 18800
        private var portCounter = 0

        @Synchronized
        fun nextPort(): Int = TEST_PORT_BASE + (portCounter++)
    }

    // ==================== Helper: Mock Lua Client ====================

    /**
     * Simulates a LuaPanda.lua client that connects to the IDE server,
     * receives commands, and sends events/responses.
     */
    private class MockLuaClient(private val host: String, private val port: Int) {
        var socket: Socket? = null
        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null
        val receivedMessages = ConcurrentLinkedQueue<JsonObject>()
        private val splitChar = ProtocolConstants.TCP_SPLIT_CHAR

        fun connect() {
            socket = Socket(host, port)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8))
        }

        /**
         * Send a LuaPanda protocol frame: JSON + " |*|\n"
         */
        fun sendFrame(json: JsonObject) {
            val frame = "${json} $splitChar\n"
            writer?.write(frame)
            writer?.flush()
        }

        /**
         * Read one complete message from the server.
         * Blocks until a full frame is received.
         */
        fun readMessage(): JsonObject? {
            val line = reader?.readLine() ?: return null
            // Remove trailing |*| and trim
            val parts = line.split(splitChar)
            val jsonStr = parts[0].trim()
            if (jsonStr.isEmpty()) return null
            return JsonParser.parseString(jsonStr).asJsonObject
        }

        /**
         * Read messages in a background thread, accumulating into receivedMessages.
         */
        fun startReadLoop(): Thread {
            val thread = Thread {
                try {
                    var cutoff = ""
                    while (!Thread.currentThread().isInterrupted && socket?.isClosed == false) {
                        val line = reader?.readLine() ?: break
                        val rawData = cutoff + line
                        cutoff = ""

                        if (rawData.contains(splitChar)) {
                            val parts = rawData.split(splitChar)
                            for (i in 0 until parts.size - 1) {
                                val msg = parts[i].trim()
                                if (msg.isNotEmpty()) {
                                    receivedMessages.add(JsonParser.parseString(msg).asJsonObject)
                                }
                            }
                            val last = parts.last().trim()
                            if (last.isNotEmpty()) cutoff = last
                        } else {
                            cutoff = rawData
                        }
                    }
                } catch (_: Exception) {
                }
            }
            thread.isDaemon = true
            thread.start()
            return thread
        }

        fun disconnect() {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    // ==================== Tests ====================

    @Test
    fun `test basic connection and message exchange`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        val messageLatch = CountDownLatch(1)
        val receivedByIde = ConcurrentLinkedQueue<JsonObject>()

        // Start "IDE" server in background
        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                val processor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {
                        receivedByIde.add(message)
                        messageLatch.countDown()
                    }

                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                processor.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        // Mock Lua client connects and sends a breakpoint event
        Thread.sleep(200) // Wait for server to start
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()

        val event = JsonObject().apply {
            addProperty("cmd", ProtocolConstants.EVT_STOP_ON_CODE_BREAKPOINT)
            addProperty("callbackId", "0")
            val stack = JsonArray()
            val frame = JsonObject().apply {
                addProperty("file", "test.lua")
                addProperty("line", 42)
                addProperty("func", "main")
                addProperty("level", 0)
            }
            stack.add(frame)
            add("info", stack)
        }
        mockClient.sendFrame(event)

        // Wait for IDE to receive the message
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Should receive message within timeout")
        assertEquals(1, receivedByIde.size)

        val received = receivedByIde.poll()!!
        assertEquals("stopOnCodeBreakpoint", received.get("cmd").asString)

        // Cleanup
        mockClient.disconnect()
        serverSocket.close()
    }

    @Test
    fun `test IDE sends command to Lua client`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        var ideProcessor: ProtocolProcessor? = null
        val connectedLatch = CountDownLatch(1)

        // Start IDE server
        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                ideProcessor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {}
                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                connectedLatch.countDown()
                ideProcessor?.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()
        val readThread = mockClient.startReadLoop()

        // Wait for processor to be created
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // Let processor.start() begin

        // IDE sends "continue" command
        ideProcessor?.sendCommand(ProtocolConstants.CMD_CONTINUE)

        // Wait for mock client to receive
        Thread.sleep(500)
        assertTrue(mockClient.receivedMessages.size > 0, "Mock client should receive the continue command")

        val received = mockClient.receivedMessages.poll()!!
        assertEquals("continue", received.get("cmd").asString)
        assertEquals("0", received.get("callbackId").asString)

        // Cleanup
        mockClient.disconnect()
        ideProcessor?.stop()
        serverSocket.close()
    }

    @Test
    fun `test callbackId request-response matching`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        var ideProcessor: ProtocolProcessor? = null
        val connectedLatch = CountDownLatch(1)
        val callbackLatch = CountDownLatch(1)
        var callbackResponse: JsonObject? = null

        // Start IDE server
        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                ideProcessor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {}
                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                connectedLatch.countDown()
                ideProcessor?.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()
        val readThread = mockClient.startReadLoop()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        // IDE sends getVariable with callback
        val info = JsonObject().apply {
            addProperty("frameId", 0)
        }
        ideProcessor?.sendCommand(ProtocolConstants.CMD_GET_VARIABLE, info) { response ->
            callbackResponse = response
            callbackLatch.countDown()
        }

        // Wait for mock client to receive the command
        Thread.sleep(500)
        assertTrue(mockClient.receivedMessages.size > 0, "Mock client should receive getVariable")

        val request = mockClient.receivedMessages.poll()!!
        assertEquals("getVariable", request.get("cmd").asString)
        val callbackId = request.get("callbackId").asString
        assertNotEquals("0", callbackId, "callbackId should be non-zero for callback commands")

        // Mock client sends response with matching callbackId
        val response = JsonObject().apply {
            addProperty("cmd", "wireResponse")
            addProperty("callbackId", callbackId)
            val vars = JsonArray()
            val v = JsonObject().apply {
                addProperty("name", "x")
                addProperty("value", "42")
                addProperty("type", "number")
            }
            vars.add(v)
            add("info", vars)
        }
        mockClient.sendFrame(response)

        // Wait for callback to be invoked
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback should be invoked within timeout")
        assertNotNull(callbackResponse)
        assertEquals(callbackId, callbackResponse!!.get("callbackId").asString)

        // Cleanup
        mockClient.disconnect()
        ideProcessor?.stop()
        serverSocket.close()
    }

    @Test
    fun `test sticky packet handling - multiple messages in one TCP write`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        val receivedByIde = ConcurrentLinkedQueue<JsonObject>()
        val allReceivedLatch = CountDownLatch(3)

        // Start IDE server
        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                val processor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {
                        receivedByIde.add(message)
                        allReceivedLatch.countDown()
                    }

                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                processor.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()

        // Send 3 messages in a single TCP write (simulating sticky packets)
        val msg1 = """{"cmd":"output","callbackId":"0","info":{"logInfo":"line1"}}"""
        val msg2 = """{"cmd":"tip","callbackId":"0","info":{"logInfo":"hello"}}"""
        val msg3 = """{"cmd":"tipError","callbackId":"0","info":{"logInfo":"oops"}}"""
        val stickyData = "$msg1 $splitChar\n$msg2 $splitChar\n$msg3 $splitChar\n"
        mockClient.writer?.write(stickyData)
        mockClient.writer?.flush()

        // Wait for all 3 messages
        assertTrue(allReceivedLatch.await(5, TimeUnit.SECONDS), "Should receive all 3 messages from sticky packet")
        assertEquals(3, receivedByIde.size)

        val r1 = receivedByIde.poll()!!
        val r2 = receivedByIde.poll()!!
        val r3 = receivedByIde.poll()!!
        assertEquals("output", r1.get("cmd").asString)
        assertEquals("tip", r2.get("cmd").asString)
        assertEquals("tipError", r3.get("cmd").asString)

        // Cleanup
        mockClient.disconnect()
        serverSocket.close()
    }

    @Test
    fun `test disconnection is detected`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        val disconnectedLatch = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                val processor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {}
                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {
                        disconnectedLatch.countDown()
                    }

                    override fun onError(error: Exception) {}
                })
                processor.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()

        // Send one message then disconnect
        val msg = JsonObject().apply {
            addProperty("cmd", "output")
            addProperty("callbackId", "0")
        }
        mockClient.sendFrame(msg)
        Thread.sleep(200)

        // Close the client connection
        mockClient.disconnect()

        // The IDE should detect disconnection
        assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS), "Should detect client disconnection")

        serverSocket.close()
    }

    @Test
    fun `test full debug session simulation`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        var ideProcessor: ProtocolProcessor? = null
        val connectedLatch = CountDownLatch(1)
        val receivedByIde = ConcurrentLinkedQueue<JsonObject>()

        // Start IDE server
        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                ideProcessor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {
                        receivedByIde.add(message)
                    }

                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                connectedLatch.countDown()
                ideProcessor?.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()
        val readThread = mockClient.startReadLoop()

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        // Step 1: IDE sends initSuccess
        val initInfo = JsonObject().apply {
            addProperty("stopOnEntry", false)
            addProperty("pathCaseSensitivity", true)
            addProperty("autoPathMode", true)
            addProperty("logLevel", 1)
        }
        ideProcessor?.sendCommand(ProtocolConstants.CMD_INIT_SUCCESS, initInfo)
        Thread.sleep(300)
        assertTrue(mockClient.receivedMessages.size > 0)
        val initMsg = mockClient.receivedMessages.poll()!!
        assertEquals("initSuccess", initMsg.get("cmd").asString)

        // Step 2: IDE sends setBreakPoint
        val bpInfo = JsonObject().apply {
            val bps = JsonArray()
            val bp = JsonObject().apply {
                addProperty("file", "test.lua")
                addProperty("line", 10)
            }
            bps.add(bp)
            add("breakpoints", bps)
        }
        ideProcessor?.sendCommand(ProtocolConstants.CMD_SET_BREAKPOINTS, bpInfo)
        Thread.sleep(300)
        val bpMsg = mockClient.receivedMessages.poll()!!
        assertEquals("setBreakPoint", bpMsg.get("cmd").asString)

        // Step 3: Lua hits breakpoint, sends stopOnCodeBreakpoint event
        val stopEvent = JsonObject().apply {
            addProperty("cmd", "stopOnCodeBreakpoint")
            addProperty("callbackId", "0")
            val stack = JsonArray()
            val frame = JsonObject().apply {
                addProperty("file", "test.lua")
                addProperty("line", 10)
                addProperty("func", "main")
                addProperty("level", 0)
            }
            stack.add(frame)
            add("info", stack)
        }
        mockClient.sendFrame(stopEvent)
        Thread.sleep(300)
        assertEquals(1, receivedByIde.size)
        val stopMsg = receivedByIde.poll()!!
        assertEquals("stopOnCodeBreakpoint", stopMsg.get("cmd").asString)

        // Step 4: IDE sends continue
        ideProcessor?.sendCommand(ProtocolConstants.CMD_CONTINUE)
        Thread.sleep(300)
        val contMsg = mockClient.receivedMessages.poll()!!
        assertEquals("continue", contMsg.get("cmd").asString)

        // Step 5: IDE sends stopRun
        ideProcessor?.sendCommand(ProtocolConstants.CMD_STOP)
        Thread.sleep(300)
        val stopRunMsg = mockClient.receivedMessages.poll()!!
        assertEquals("stopRun", stopRunMsg.get("cmd").asString)

        // Cleanup
        mockClient.disconnect()
        ideProcessor?.stop()
        serverSocket.close()
    }

    @Test
    fun `test protocol constants match wire format`() {
        // Verify the frame format matches what LuaPanda.lua expects
        val body = """{"cmd":"continue","callbackId":"0"}"""
        val frame = "$body ${ProtocolConstants.TCP_SPLIT_CHAR}\n"

        // Frame should contain: JSON + space + |*| + newline
        assertTrue(frame.startsWith("{"))
        assertTrue(frame.contains("|*|"))
        assertTrue(frame.endsWith("\n"))

        // Parse back the JSON from the frame
        val parts = frame.split(ProtocolConstants.TCP_SPLIT_CHAR)
        val jsonStr = parts[0].trim()
        val parsed = JsonParser.parseString(jsonStr).asJsonObject
        assertEquals("continue", parsed.get("cmd").asString)
    }

    @Test
    fun `test evaluate expression roundtrip`() {
        val port = nextPort()
        val serverSocket = ServerSocket(port)
        var ideProcessor: ProtocolProcessor? = null
        val connectedLatch = CountDownLatch(1)
        val evalLatch = CountDownLatch(1)
        var evalResult: JsonObject? = null

        val serverThread = Thread {
            try {
                val clientSocket = serverSocket.accept()
                ideProcessor = ProtocolProcessor(clientSocket, object : ProtocolProcessor.ProtocolListener {
                    override fun onMessage(message: JsonObject) {}
                    override fun onDisconnected(reason: String, connectionDurationMs: Long) {}
                    override fun onError(error: Exception) {}
                })
                connectedLatch.countDown()
                ideProcessor?.start()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        Thread.sleep(200)
        val mockClient = MockLuaClient("127.0.0.1", port)
        mockClient.connect()
        val readThread = mockClient.startReadLoop()
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        // IDE evaluates "1 + 2"
        val evalInfo = JsonObject().apply {
            addProperty("expression", "1 + 2")
            addProperty("frameId", 0)
        }
        ideProcessor?.sendCommand(ProtocolConstants.CMD_GET_WATCHED_VARIABLE, evalInfo) { response ->
            evalResult = response
            evalLatch.countDown()
        }

        Thread.sleep(500)
        val request = mockClient.receivedMessages.poll()!!
        assertEquals("getWatchedVariable", request.get("cmd").asString)
        val cbId = request.get("callbackId").asString

        // Mock Lua sends back the result
        val response = JsonObject().apply {
            addProperty("cmd", "wireResponse")
            addProperty("callbackId", cbId)
            val vars = JsonArray()
            val v = JsonObject().apply {
                addProperty("name", "result")
                addProperty("value", "3")
                addProperty("type", "number")
            }
            vars.add(v)
            add("info", vars)
        }
        mockClient.sendFrame(response)

        assertTrue(evalLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(evalResult)
        val infoArr = evalResult!!.getAsJsonArray("info")
        assertEquals("3", infoArr[0].asJsonObject.get("value").asString)

        mockClient.disconnect()
        ideProcessor?.stop()
        serverSocket.close()
    }
}
