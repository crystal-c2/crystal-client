package com.crystalc2.client

import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import proxy.Proxy.ProxyFrame
import proxy.Proxy.ProxyFrameType
import proxy.ProxyServiceGrpc
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

object Socks5Proxy {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var proxyStream: StreamObserver<ProxyFrame>? = null
    private val streamLock = Any()

    private val nextId = AtomicInteger(0)
    private val connections = ConcurrentHashMap<Int, Connection>()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start(beaconId: Int, listenPort: Int) {
        stop()
        val ss = ServerSocket(listenPort)
        serverSocket = ss
        Thread({
            try {
                while (!ss.isClosed) {
                    val client = ss.accept()
                    Thread({ handleConnection(beaconId, client) }, "socks5-conn-${nextId.get()}").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: IOException) { /* server closed */ }
        }, "socks5-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        synchronized(streamLock) {
            proxyStream?.onCompleted()
            proxyStream = null
        }
        connections.values.forEach { it.close() }
        connections.clear()
        serverSocket?.close()
        serverSocket = null
    }

    // -------------------------------------------------------------------------
    // gRPC stream
    // -------------------------------------------------------------------------

    private fun ensureProxyStream(): StreamObserver<ProxyFrame> = synchronized(streamLock) {
        proxyStream ?: ProxyServiceGrpc.newStub(GrpcClient.channel)
            .proxyStream(object : StreamObserver<ProxyFrame> {
                override fun onNext(frame: ProxyFrame) {
                    val conn = connections[frame.connectionId] ?: return
                    when (frame.type) {
                        ProxyFrameType.PROXY_FRAME_TYPE_DATA  -> conn.onServerFrame(frame.data.toByteArray(), close = false)
                        ProxyFrameType.PROXY_FRAME_TYPE_CLOSE -> conn.onServerFrame(frame.data.toByteArray(), close = true)
                        else -> {}
                    }
                }
                override fun onError(t: Throwable) {
                    System.err.println("[Socks5Proxy] stream error: ${t.message}")
                    synchronized(streamLock) { proxyStream = null }
                    connections.values.forEach { it.close() }
                    connections.clear()
                }
                override fun onCompleted() {
                    synchronized(streamLock) { proxyStream = null }
                }
            }).also { proxyStream = it }
    }

    // -------------------------------------------------------------------------
    // Per-connection handler (runs on its own daemon thread)
    // -------------------------------------------------------------------------

    private fun handleConnection(beaconId: Int, clientSocket: Socket) {
        clientSocket.use {
            try {
                val dis    = DataInputStream(clientSocket.getInputStream())
                val output = clientSocket.getOutputStream()

                val (host, port) = readSocks5Handshake(dis, output) ?: return

                val id   = nextId.incrementAndGet()
                val conn = Connection(id, clientSocket)
                connections[id] = conn

                try {
                    val stream = ensureProxyStream()

                    stream.onNext(
                        ProxyFrame.newBuilder()
                            .setBeaconId(beaconId)
                            .setConnectionId(id)
                            .setType(ProxyFrameType.PROXY_FRAME_TYPE_CONNECT)
                            .setTargetHost(host)
                            .setTargetPort(port)
                            .build()
                    )

                    val connected = try {
                        conn.connectFuture.get(30L, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) { false }
                      catch (_: Exception)         { false }

                    if (!connected) {
                        sendSocks5Reply(output, 5) // connection refused
                        return
                    }

                    sendSocks5Reply(output, 0) // success

                    // Forward data from the local tool to the agent
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = try { dis.read(buf) } catch (_: IOException) { -1 }
                        if (n < 0) break
                        if (!conn.closed) {
                            stream.onNext(
                                ProxyFrame.newBuilder()
                                    .setBeaconId(beaconId)
                                    .setConnectionId(id)
                                    .setType(ProxyFrameType.PROXY_FRAME_TYPE_DATA)
                                    .setData(ByteString.copyFrom(buf, 0, n))
                                    .build()
                            )
                        }
                    }

                    // Local tool closed — notify agent
                    if (!conn.closed) {
                        stream.onNext(
                            ProxyFrame.newBuilder()
                                .setBeaconId(beaconId)
                                .setConnectionId(id)
                                .setType(ProxyFrameType.PROXY_FRAME_TYPE_CLOSE)
                                .build()
                        )
                    }
                } finally {
                    connections.remove(id)
                    conn.close()
                }
            } catch (e: Exception) {
                System.err.println("[Socks5Proxy] connection error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // SOCKS5 handshake
    // -------------------------------------------------------------------------

    /** Returns (host, port) or null if the request is unsupported / malformed. */
    private fun readSocks5Handshake(dis: DataInputStream, output: OutputStream): Pair<String, Int>? {
        return try {
            // ---- auth negotiation ----
            if (dis.read() != 5) return null          // VER
            val nmethods = dis.read()
            repeat(nmethods) { dis.read() }            // skip offered methods
            output.write(byteArrayOf(5, 0))            // VER=5, METHOD=0 (no auth)
            output.flush()

            // ---- CONNECT request ----
            if (dis.read() != 5) return null           // VER
            if (dis.read() != 1) return null           // CMD must be CONNECT
            dis.read()                                 // RSV (reserved)

            val host: String = when (dis.read()) {     // ATYP
                1 -> {                                 // IPv4
                    val b = ByteArray(4).also { dis.readFully(it) }
                    "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}" +
                    ".${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                }
                3 -> {                                 // Domain name
                    val len = dis.read()
                    String(ByteArray(len).also { dis.readFully(it) })
                }
                else -> return null                    // IPv6 not supported
            }

            val port = (dis.read() shl 8) or dis.read()
            host to port
        } catch (_: IOException) { null }
    }

    private fun sendSocks5Reply(output: OutputStream, rep: Int) {
        // VER=5 | REP | RSV=0 | ATYP=1 (IPv4) | BND.ADDR=0.0.0.0 | BND.PORT=0
        output.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    // -------------------------------------------------------------------------
    // Per-connection state
    // -------------------------------------------------------------------------

    private class Connection(val id: Int, private val socket: Socket) {
        val connectFuture = CompletableFuture<Boolean>()
        @Volatile var closed = false

        /** Called from the gRPC onNext thread. */
        fun onServerFrame(data: ByteArray, close: Boolean) {
            // First frame for this connection resolves the CONNECT handshake.
            if (!connectFuture.isDone) {
                connectFuture.complete(!close)
            }
            if (data.isNotEmpty()) {
                try {
                    socket.getOutputStream().let { out ->
                        out.write(data)
                        out.flush()
                    }
                } catch (_: IOException) {}
            }
            if (close) close()
        }

        fun close() {
            if (closed) return
            closed = true
            if (!connectFuture.isDone) connectFuture.complete(false)
            try { socket.close() } catch (_: IOException) {}
        }
    }
}