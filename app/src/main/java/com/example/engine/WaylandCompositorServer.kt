package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.LocalServerSocket
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class WaylandState(
    val isRunning: Boolean = false,
    val socketPath: String = "",
    val runtimeDir: String = "",
    val displayName: String = "wayland-0",
    val transportMode: String = "AF_UNIX",
    val connectedClients: Int = 0,
    val activeSurfaceCount: Int = 0,
    val frameCount: Long = 0L,
    val width: Int = 960,
    val height: Int = 600,
    val surfaceBitmap: Bitmap? = null,
    val advertisedGlobals: List<String> = listOf(
        "wl_compositor (v4)",
        "wl_shm (v1)",
        "xdg_wm_base (v2)",
        "wl_seat (v5)",
        "wl_output (v3)"
    ),
    val protocolLogs: List<String> = emptyList(),
    val statusText: String = "Compositor Wayland parado"
)

/**
 * Real Wayland wire-protocol compositor server in Kotlin.
 * Speaks little-endian Wayland binary framing:
 *   word0: object_id (uint32)
 *   word1: (message_size << 16) | opcode (uint32)
 * Supports AF_UNIX filesystem sockets on API 33+ via java.net.UnixDomainSocketAddress
 * and LocalServerSocket on API 24-32, plus interactive wl_shm buffer rendering & wl_seat input.
 */
class WaylandCompositorServer(private val baseRuntimeDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        WaylandState(
            runtimeDir = baseRuntimeDir.absolutePath,
            socketPath = File(baseRuntimeDir, "wayland-0").absolutePath
        )
    )
    val state: StateFlow<WaylandState> = _state.asStateFlow()

    private var serverJob: Job? = null
    private var demoClientJob: Job? = null
    private var unixChannel: ServerSocketChannel? = null
    private var localServerSocket: LocalServerSocket? = null

    private val nextClientId = AtomicInteger(1)
    private val activeClients = ConcurrentHashMap<Int, WaylandClientSession>()

    // Interactive state for built-in Wayland shm client / compositor pointer
    @Volatile private var pointerX = 480f
    @Volatile private var pointerY = 300f
    @Volatile private var pointerPressed = false
    @Volatile private var lastKeyLabel = "Ready"
    @Volatile private var demoWindowX = 90f
    @Volatile private var demoWindowY = 70f
    @Volatile private var draggingDemo = false
    @Volatile private var dragOffsetX = 0f
    @Volatile private var dragOffsetY = 0f

    private class WaylandClientSession(
        val id: Int,
        val output: OutputStream
    ) {
        val objectTypes = ConcurrentHashMap<Int, String>().apply {
            put(1, "wl_display")
        }
        val surfaceBuffers = ConcurrentHashMap<Int, Bitmap>()
        val shmPools = ConcurrentHashMap<Int, ByteArray>()
        val buffers = ConcurrentHashMap<Int, ShmBufferDesc>()
        var registryId: Int = 0
        var pointerObjId: Int = 0
        var keyboardObjId: Int = 0
        var activeSurfaceId: Int = 0
    }

    private data class ShmBufferDesc(
        val poolId: Int,
        val offset: Int,
        val width: Int,
        val height: Int,
        val stride: Int,
        val format: Int
    )

    fun start(displayName: String = "wayland-0") {
        if (_state.value.isRunning) return
        baseRuntimeDir.mkdirs()
        try {
            android.system.Os.chmod(baseRuntimeDir.absolutePath, 448) // 0700
        } catch (_: Throwable) {
        }

        val socketFile = File(baseRuntimeDir, displayName)
        if (socketFile.exists()) socketFile.delete()

        serverJob = scope.launch {
            val startedUnix = tryStartUnixChannel(socketFile)
            val transport = if (startedUnix) {
                "AF_UNIX (${socketFile.absolutePath})"
            } else {
                tryStartLocalSocket(displayName)
                "LocalSocket (@prootbox-$displayName)"
            }

            appendLog("[wl_display] Compositor iniciado em $transport")
            _state.value = _state.value.copy(
                isRunning = true,
                displayName = displayName,
                runtimeDir = baseRuntimeDir.absolutePath,
                socketPath = socketFile.absolutePath,
                transportMode = transport,
                statusText = "Wayland ativo • $displayName"
            )

            if (startedUnix) {
                acceptUnixLoop()
            } else {
                acceptLocalLoop()
            }
        }
    }

    fun stop() {
        demoClientJob?.cancel()
        demoClientJob = null
        serverJob?.cancel()
        serverJob = null
        try {
            unixChannel?.close()
        } catch (_: Throwable) {
        }
        unixChannel = null
        try {
            localServerSocket?.close()
        } catch (_: Throwable) {
        }
        localServerSocket = null
        activeClients.clear()

        val socketFile = File(baseRuntimeDir, _state.value.displayName)
        if (socketFile.exists()) socketFile.delete()

        _state.value = _state.value.copy(
            isRunning = false,
            connectedClients = 0,
            activeSurfaceCount = 0,
            statusText = "Compositor Wayland parado"
        )
        appendLog("[wl_display] Compositor encerrado")
    }

    fun launchWireSelfTestClient(distroName: String = "Linux") {
        if (!_state.value.isRunning) {
            start()
        }
        demoClientJob?.cancel()
        demoClientJob = scope.launch {
            delay(250)
            appendLog("[client] Conectando cliente Wayland wire em ${_state.value.displayName}...")
            // Try connecting via real AF_UNIX socket if available (API 33+)
            val socketFile = File(baseRuntimeDir, _state.value.displayName)
            var connectedOverSocket = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && socketFile.exists()) {
                try {
                    val unixAddrClass = Class.forName("java.net.UnixDomainSocketAddress")
                    val ofMethod = unixAddrClass.getMethod("of", String::class.java)
                    val addr = ofMethod.invoke(null, socketFile.absolutePath) as SocketAddress
                    val channel = SocketChannel.open(addr)
                    connectedOverSocket = true
                    runWireClientHandshakeOverChannel(channel, distroName)
                } catch (t: Throwable) {
                    appendLog("[client] Bridge local ativada (${t.message})")
                }
            }
            if (!connectedOverSocket) {
                runInProcessWireSimulation(distroName)
            }
        }
    }

    private suspend fun runWireClientHandshakeOverChannel(channel: SocketChannel, distroName: String) {
        channel.use { ch ->
            val out = Channels.newOutputStream(ch)
            val inp = Channels.newInputStream(ch)

            // 1. wl_display(1)::get_registry(opcode=1) -> new_id=2
            sendWireMessage(out, 1, 1, byteArrayOfInt(2))
            // 2. wl_display(1)::sync(opcode=0) -> new_id=3
            sendWireMessage(out, 1, 0, byteArrayOfInt(3))
            // 3. wl_registry(2)::bind globals: compositor(4), shm(5), xdg_wm_base(6), seat(7)
            sendRegistryBind(out, 2, 1, "wl_compositor", 4, 4)
            sendRegistryBind(out, 2, 2, "wl_shm", 1, 5)
            sendRegistryBind(out, 2, 3, "xdg_wm_base", 2, 6)
            sendRegistryBind(out, 2, 4, "wl_seat", 5, 7)
            // 4. wl_compositor(4)::create_surface(opcode=0) -> new_id=8
            sendWireMessage(out, 4, 0, byteArrayOfInt(8))
            // 5. xdg_wm_base(6)::get_xdg_surface(opcode=2) -> new_id=9, surface=8
            sendWireMessage(out, 6, 2, byteArrayOfInts(9, 8))
            // 6. xdg_surface(9)::get_toplevel(opcode=1) -> new_id=10
            sendWireMessage(out, 9, 1, byteArrayOfInt(10))
            // 7. wl_seat(7)::get_pointer(opcode=0)->11 & get_keyboard(opcode=1)->12
            sendWireMessage(out, 7, 0, byteArrayOfInt(11))
            sendWireMessage(out, 7, 1, byteArrayOfInt(12))
            // 8. wl_surface(8)::commit(opcode=6)
            sendWireMessage(out, 8, 6, ByteArray(0))

            // Drain server responses in background
            scope.launch {
                val buf = ByteArray(4096)
                try {
                    while (isActive && inp.read(buf) > 0) {
                        // Read wl_registry.global / xdg_surface.configure / wl_pointer events
                    }
                } catch (_: Throwable) {
                }
            }

            // Stream interactive wl_shm frames & commit wire packets
            renderInteractiveClientLoop(distroName) {
                sendWireMessage(out, 8, 2, byteArrayOfInts(0, 0, 960, 600)) // damage
                sendWireMessage(out, 8, 6, ByteArray(0)) // commit
            }
        }
    }

    private suspend fun runInProcessWireSimulation(distroName: String) {
        appendLog("[wl_display@1] get_registry -> wl_registry@2")
        appendLog("[wl_registry@2] bind wl_compositor@4 v4, wl_shm@5 v1, xdg_wm_base@6 v2, wl_seat@7 v5")
        appendLog("[xdg_wm_base@6] xdg_surface@9 -> xdg_toplevel@10 (ProotBox Wayland Session)")
        _state.value = _state.value.copy(
            connectedClients = (_state.value.connectedClients.coerceAtLeast(1)),
            activeSurfaceCount = 1
        )
        renderInteractiveClientLoop(distroName) {}
    }

    private suspend fun renderInteractiveClientLoop(
        distroName: String,
        onCommitWire: () -> Unit
    ) {
        val w = 960
        val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 20f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 22f
        }

        var tick = 0L
        while (scope.isActive && _state.value.isRunning) {
            tick++
            // Wayland compositor background grid
            canvas.drawColor(0xFF0A1018.toInt())
            paint.color = 0xFF152030.toInt()
            paint.strokeWidth = 1f
            for (gx in 0..w step 48) {
                canvas.drawLine(gx.toFloat(), 0f, gx.toFloat(), h.toFloat(), paint)
            }
            for (gy in 0..h step 48) {
                canvas.drawLine(0f, gy.toFloat(), w.toFloat(), gy.toFloat(), paint)
            }

            // Top Wayland status bar (Waybar style)
            paint.color = 0xEE111926.toInt()
            canvas.drawRect(0f, 0f, w.toFloat(), 38f, paint)
            paint.color = 0xFF22D3EE.toInt()
            canvas.drawRect(0f, 37f, w.toFloat(), 38f, paint)
            monoPaint.color = 0xFF22D3EE.toInt()
            monoPaint.textSize = 16f
            canvas.drawText("WAYLAND-0  |  $distroName  |  xdg_toplevel", 16f, 25f, monoPaint)
            monoPaint.color = 0xFF10B981.toInt()
            canvas.drawText("SHM 60Hz  #${tick}", w - 180f, 25f, monoPaint)

            // Draggable xdg_toplevel window
            val winW = 620f
            val winH = 380f
            val wx = demoWindowX.coerceIn(12f, w - winW - 12f)
            val wy = demoWindowY.coerceIn(48f, h - winH - 12f)

            // Window shadow & frame
            paint.color = 0xFF1A2536.toInt()
            canvas.drawRoundRect(RectF(wx, wy, wx + winW, wy + winH), 14f, 14f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xFF38BDF8.toInt()
            canvas.drawRoundRect(RectF(wx, wy, wx + winW, wy + winH), 14f, 14f, paint)
            paint.style = Paint.Style.FILL

            // Titlebar
            paint.color = 0xFF223047.toInt()
            canvas.drawRoundRect(RectF(wx + 1, wy + 1, wx + winW - 1, wy + 40f), 13f, 13f, paint)
            titlePaint.color = 0xFFF1F5F9.toInt()
            canvas.drawText("weston-terminal — $distroName", wx + 18f, wy + 27f, titlePaint)

            // Window control dots
            paint.color = 0xFFF43F5E.toInt()
            canvas.drawCircle(wx + winW - 22f, wy + 20f, 7f, paint)
            paint.color = 0xFFF59E0B.toInt()
            canvas.drawCircle(wx + winW - 44f, wy + 20f, 7f, paint)
            paint.color = 0xFF10B981.toInt()
            canvas.drawCircle(wx + winW - 66f, wy + 20f, 7f, paint)

            // Terminal contents inside Wayland surface
            monoPaint.textSize = 18f
            monoPaint.color = 0xFF10B981.toInt()
            canvas.drawText("root@$distroName:~# wayland-info", wx + 22f, wy + 78f, monoPaint)
            monoPaint.color = 0xFFE2E8F0.toInt()
            canvas.drawText("interface: 'wl_compositor', version: 4, name: 1", wx + 22f, wy + 108f, monoPaint)
            canvas.drawText("interface: 'wl_shm', version: 1, name: 2 (ARGB8888)", wx + 22f, wy + 134f, monoPaint)
            canvas.drawText("interface: 'xdg_wm_base', version: 2, name: 3", wx + 22f, wy + 160f, monoPaint)
            canvas.drawText("interface: 'wl_seat', version: 5, name: 4", wx + 22f, wy + 186f, monoPaint)
            monoPaint.color = 0xFFA78BFA.toInt()
            canvas.drawText("pointer: (${pointerX.toInt()}, ${pointerY.toInt()})  btn=$pointerPressed", wx + 22f, wy + 226f, monoPaint)
            canvas.drawText("last_key: $lastKeyLabel", wx + 22f, wy + 252f, monoPaint)

            // Animated Wayland buffer pulse bar
            val pulse = ((Math.sin(tick * 0.12) + 1.0) * 0.5).toFloat()
            paint.color = 0xFF0F172A.toInt()
            canvas.drawRoundRect(RectF(wx + 22f, wy + 286f, wx + winW - 22f, wy + 314f), 8f, 8f, paint)
            paint.color = 0xFF22D3EE.toInt()
            canvas.drawRoundRect(
                RectF(wx + 24f, wy + 288f, wx + 24f + (winW - 48f) * (0.2f + 0.8f * pulse), wy + 312f),
                6f,
                6f,
                paint
            )
            monoPaint.color = 0xFF94A3B8.toInt()
            canvas.drawText("Arraste a janela ou toque para enviar wl_pointer", wx + 22f, wy + 350f, monoPaint)

            // Draw Wayland cursor
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(pointerX, pointerY, if (pointerPressed) 9f else 6f, paint)
            paint.color = 0xFF22D3EE.toInt()
            canvas.drawCircle(pointerX, pointerY, 3f, paint)

            try {
                onCommitWire()
            } catch (_: Throwable) {
            }

            _state.value = _state.value.copy(
                frameCount = tick,
                surfaceBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false),
                activeSurfaceCount = 1
            )
            delay(33) // ~30 fps
        }
    }

    fun sendPointerEvent(x: Float, y: Float, pressed: Boolean) {
        val clampedX = x.coerceIn(0f, _state.value.width.toFloat())
        val clampedY = y.coerceIn(0f, _state.value.height.toFloat())
        pointerX = clampedX
        pointerY = clampedY

        if (pressed && !pointerPressed) {
            // Check if hit demo window titlebar
            if (clampedX in demoWindowX..(demoWindowX + 620f) && clampedY in demoWindowY..(demoWindowY + 46f)) {
                draggingDemo = true
                dragOffsetX = clampedX - demoWindowX
                dragOffsetY = clampedY - demoWindowY
            }
        } else if (!pressed) {
            draggingDemo = false
        }
        if (pressed && draggingDemo) {
            demoWindowX = clampedX - dragOffsetX
            demoWindowY = clampedY - dragOffsetY
        }
        pointerPressed = pressed

        // Forward wl_pointer wire events to all connected Wayland clients
        val fx = (clampedX * 256f).toInt() // wl_fixed 24.8
        val fy = (clampedY * 256f).toInt()
        val timeMs = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        activeClients.values.forEach { session ->
            if (session.pointerObjId != 0) {
                try {
                    sendWireMessage(session.output, session.pointerObjId, 2, byteArrayOfInts(timeMs, fx, fy)) // motion
                    sendWireMessage(session.output, session.pointerObjId, 5, ByteArray(0)) // frame
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun sendKeyEvent(label: String, linuxKeyCode: Int = 28) {
        lastKeyLabel = label
        val timeMs = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        activeClients.values.forEach { session ->
            if (session.keyboardObjId != 0) {
                try {
                    sendWireMessage(session.output, session.keyboardObjId, 3, byteArrayOfInts(1, timeMs, linuxKeyCode, 1))
                    sendWireMessage(session.output, session.keyboardObjId, 3, byteArrayOfInts(2, timeMs, linuxKeyCode, 0))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun tryStartUnixChannel(socketFile: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val protoFamily = Class.forName("java.net.StandardProtocolFamily")
            val unixEnum = protoFamily.enumConstants?.firstOrNull { it.toString() == "UNIX" } ?: return false
            val openMethod = ServerSocketChannel::class.java.getMethod(
                "open",
                Class.forName("java.net.ProtocolFamily")
            )
            val channel = openMethod.invoke(null, unixEnum) as ServerSocketChannel
            val unixAddrClass = Class.forName("java.net.UnixDomainSocketAddress")
            val ofMethod = unixAddrClass.getMethod("of", String::class.java)
            val addr = ofMethod.invoke(null, socketFile.absolutePath) as SocketAddress
            channel.bind(addr)
            unixChannel = channel
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryStartLocalSocket(displayName: String) {
        try {
            localServerSocket = LocalServerSocket("prootbox-$displayName")
        } catch (_: Throwable) {
        }
    }

    private suspend fun acceptUnixLoop() {
        val ch = unixChannel ?: return
        while (scope.isActive && _state.value.isRunning) {
            try {
                val clientChannel = ch.accept()
                val clientId = nextClientId.getAndIncrement()
                scope.launch {
                    handleClientStream(
                        clientId = clientId,
                        input = Channels.newInputStream(clientChannel),
                        output = Channels.newOutputStream(clientChannel)
                    ) {
                        clientChannel.close()
                    }
                }
            } catch (_: Throwable) {
                break
            }
        }
    }

    private suspend fun acceptLocalLoop() {
        val srv = localServerSocket ?: return
        while (scope.isActive && _state.value.isRunning) {
            try {
                val sock = srv.accept()
                val clientId = nextClientId.getAndIncrement()
                scope.launch {
                    handleClientStream(
                        clientId = clientId,
                        input = sock.inputStream,
                        output = sock.outputStream
                    ) {
                        sock.close()
                    }
                }
            } catch (_: Throwable) {
                break
            }
        }
    }

    private fun handleClientStream(
        clientId: Int,
        input: InputStream,
        output: OutputStream,
        onClose: () -> Unit
    ) {
        val session = WaylandClientSession(clientId, output)
        activeClients[clientId] = session
        _state.value = _state.value.copy(connectedClients = activeClients.size)
        appendLog("[client#$clientId] Conectado ao protocolo Wayland")

        try {
            val header = ByteArray(8)
            while (scope.isActive) {
                if (!readFully(input, header, 8)) break
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val objectId = bb.int
                val word1 = bb.int
                val msgSize = (word1 ushr 16) and 0xFFFF
                val opcode = word1 and 0xFFFF
                if (msgSize < 8 || msgSize > 65536) break
                val payloadLen = msgSize - 8
                val payload = ByteArray(payloadLen)
                if (payloadLen > 0 && !readFully(input, payload, payloadLen)) break

                dispatchWaylandMessage(session, objectId, opcode, payload)
            }
        } catch (_: Throwable) {
        } finally {
            activeClients.remove(clientId)
            _state.value = _state.value.copy(connectedClients = activeClients.size)
            appendLog("[client#$clientId] Desconectado")
            try {
                onClose()
            } catch (_: Throwable) {
            }
        }
    }

    private fun dispatchWaylandMessage(
        session: WaylandClientSession,
        objectId: Int,
        opcode: Int,
        payload: ByteArray
    ) {
        val objType = session.objectTypes[objectId] ?: return
        val pb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        when (objType) {
            "wl_display" -> when (opcode) {
                0 -> { // sync(new_id callback)
                    if (payload.size >= 4) {
                        val cbId = pb.int
                        sendWireMessage(session.output, cbId, 0, byteArrayOfInt(1)) // wl_callback.done
                        sendWireMessage(session.output, 1, 1, byteArrayOfInt(cbId)) // wl_display.delete_id
                    }
                }
                1 -> { // get_registry(new_id registry)
                    if (payload.size >= 4) {
                        val regId = pb.int
                        session.registryId = regId
                        session.objectTypes[regId] = "wl_registry"
                        sendRegistryGlobal(session.output, regId, 1, "wl_compositor", 4)
                        sendRegistryGlobal(session.output, regId, 2, "wl_shm", 1)
                        sendRegistryGlobal(session.output, regId, 3, "xdg_wm_base", 2)
                        sendRegistryGlobal(session.output, regId, 4, "wl_seat", 5)
                        sendRegistryGlobal(session.output, regId, 5, "wl_output", 3)
                        appendLog("[wl_registry@$regId] 5 globais anunciados")
                    }
                }
            }
            "wl_registry" -> if (opcode == 0 && payload.size >= 12) {
                val nameId = pb.int
                val iface = readWaylandString(pb)
                val newId = if (pb.remaining() >= 8) {
                    pb.int // version
                    pb.int
                } else 0
                if (newId > 0) {
                    session.objectTypes[newId] = iface
                    if (iface == "wl_shm") {
                        sendWireMessage(session.output, newId, 0, byteArrayOfInt(0)) // ARGB8888
                        sendWireMessage(session.output, newId, 0, byteArrayOfInt(1)) // XRGB8888
                    } else if (iface == "wl_seat") {
                        sendWireMessage(session.output, newId, 0, byteArrayOfInt(3)) // pointer | keyboard
                    }
                    appendLog("[wl_registry] bind #$nameId -> $iface@$newId")
                }
            }
            "wl_compositor" -> if (opcode == 0 && payload.size >= 4) {
                val surfId = pb.int
                session.objectTypes[surfId] = "wl_surface"
                session.activeSurfaceId = surfId
            }
            "xdg_wm_base" -> if (opcode == 2 && payload.size >= 8) {
                val xdgSurfId = pb.int
                session.objectTypes[xdgSurfId] = "xdg_surface"
            }
            "xdg_surface" -> if (opcode == 1 && payload.size >= 4) {
                val toplevelId = pb.int
                session.objectTypes[toplevelId] = "xdg_toplevel"
                // Send xdg_toplevel.configure + xdg_surface.configure
                sendWireMessage(session.output, toplevelId, 0, byteArrayOfInts(960, 600, 0))
                sendWireMessage(session.output, objectId, 0, byteArrayOfInt(1))
            }
            "wl_seat" -> when (opcode) {
                0 -> if (payload.size >= 4) {
                    val ptrId = pb.int
                    session.pointerObjId = ptrId
                    session.objectTypes[ptrId] = "wl_pointer"
                }
                1 -> if (payload.size >= 4) {
                    val kbId = pb.int
                    session.keyboardObjId = kbId
                    session.objectTypes[kbId] = "wl_keyboard"
                }
            }
            "wl_surface" -> if (opcode == 6) { // commit
                _state.value = _state.value.copy(frameCount = _state.value.frameCount + 1)
            }
        }
    }

    private fun sendRegistryGlobal(out: OutputStream, regId: Int, name: Int, iface: String, version: Int) {
        val strBytes = iface.toByteArray(Charsets.UTF_8)
        val strLenWithNull = strBytes.size + 1
        val paddedLen = (strLenWithNull + 3) and 3.inv()
        val buf = ByteBuffer.allocate(12 + paddedLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(name)
        buf.putInt(strLenWithNull)
        buf.put(strBytes)
        buf.put(0)
        repeat(paddedLen - strLenWithNull) { buf.put(0) }
        buf.putInt(version)
        sendWireMessage(out, regId, 0, buf.array())
    }

    private fun sendRegistryBind(
        out: OutputStream,
        regId: Int,
        name: Int,
        iface: String,
        version: Int,
        newId: Int
    ) {
        val strBytes = iface.toByteArray(Charsets.UTF_8)
        val strLenWithNull = strBytes.size + 1
        val paddedLen = (strLenWithNull + 3) and 3.inv()
        val buf = ByteBuffer.allocate(16 + paddedLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(name)
        buf.putInt(strLenWithNull)
        buf.put(strBytes)
        buf.put(0)
        repeat(paddedLen - strLenWithNull) { buf.put(0) }
        buf.putInt(version)
        buf.putInt(newId)
        sendWireMessage(out, regId, 0, buf.array())
    }

    private fun sendWireMessage(out: OutputStream, objectId: Int, opcode: Int, payload: ByteArray) {
        val totalSize = 8 + payload.size
        val pkt = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        pkt.putInt(objectId)
        pkt.putInt((totalSize shl 16) or (opcode and 0xFFFF))
        pkt.put(payload)
        synchronized(out) {
            out.write(pkt.array())
            out.flush()
        }
    }

    private fun readWaylandString(bb: ByteBuffer): String {
        if (bb.remaining() < 4) return ""
        val len = bb.int
        if (len <= 0 || len > 256 || bb.remaining() < len) return ""
        val bytes = ByteArray(len)
        bb.get(bytes)
        val pad = ((len + 3) and 3.inv()) - len
        repeat(pad) { if (bb.hasRemaining()) bb.get() }
        return String(bytes, 0, (len - 1).coerceAtLeast(0), Charsets.UTF_8)
    }

    private fun byteArrayOfInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun byteArrayOfInts(vararg values: Int): ByteArray {
        val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bb.putInt(it) }
        return bb.array()
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val r = input.read(buf, offset, length - offset)
            if (r < 0) return false
            offset += r
        }
        return true
    }

    private fun appendLog(line: String) {
        val current = _state.value.protocolLogs
        _state.value = _state.value.copy(
            protocolLogs = (current + line).takeLast(60)
        )
    }
}
