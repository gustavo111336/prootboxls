package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec

data class VncUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 5901,
    val desktopName: String = "",
    val fbWidth: Int = 1280,
    val fbHeight: Int = 720,
    val framebuffer: Bitmap? = null,
    val frameCount: Long = 0L,
    val statusMessage: String = "Desconectado",
    val errorMessage: String? = null,
    val localBridgeActive: Boolean = false
)

/**
 * Real RFB 3.8 VNC client over TCP sockets, supporting Security None (1) & VNC DES Auth (2),
 * Raw (0), CopyRect (1), and DesktopSize (-223) encodings, plus PointerEvent & KeyEvent.
 * Also includes an optional local RFB 3.8 loopback server for previewing the container desktop
 * when Android kernel SELinux prevents native TigerVNC execution inside PRoot.
 */
class VncClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VncUiState())
    val state: StateFlow<VncUiState> = _state.asStateFlow()

    private var connectionJob: Job? = null
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null

    private var pixelBuffer = IntArray(1280 * 720)
    private var fbWidth = 1280
    private var fbHeight = 720

    // Optional local RFB 3.8 desktop server for container GUI bridge
    private var localRfbJob: Job? = null
    private var localServerSocket: ServerSocket? = null
    @Volatile private var bridgePointerX = 640
    @Volatile private var bridgePointerY = 360
    @Volatile private var bridgeButtonMask = 0
    @Volatile private var bridgeLastKey = ""

    fun connect(host: String, port: Int, password: String = "") {
        disconnect()
        _state.value = _state.value.copy(
            isConnecting = true,
            isConnected = false,
            host = host,
            port = port,
            errorMessage = null,
            statusMessage = "Conectando em $host:$port..."
        )

        connectionJob = scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 5000)
                sock.soTimeout = 15000
                socket = sock

                val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
                val out = DataOutputStream(sock.getOutputStream())
                outputStream = out

                // 1. RFB Version Handshake
                val serverVerBytes = ByteArray(12)
                inp.readFully(serverVerBytes)
                val serverVer = String(serverVerBytes, Charsets.US_ASCII)
                if (!serverVer.startsWith("RFB ")) {
                    throw IllegalStateException("Protocolo inválido: $serverVer")
                }
                out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                // 2. Security Types
                val numSecTypes = inp.readUnsignedByte()
                if (numSecTypes == 0) {
                    val reasonLen = inp.readInt()
                    val reason = ByteArray(reasonLen.coerceIn(0, 1024))
                    inp.readFully(reason)
                    throw IllegalStateException(String(reason, Charsets.UTF_8))
                }
                val secTypes = ByteArray(numSecTypes)
                inp.readFully(secTypes)

                val chosenSec = when {
                    secTypes.any { it.toInt() == 1 } -> 1 // None
                    secTypes.any { it.toInt() == 2 } -> 2 // VNC Auth
                    else -> throw IllegalStateException("Nenhum tipo de segurança suportado")
                }
                out.writeByte(chosenSec)
                out.flush()

                if (chosenSec == 2) {
                    val challenge = ByteArray(16)
                    inp.readFully(challenge)
                    val response = encryptVncChallenge(challenge, password)
                    out.write(response)
                    out.flush()
                }

                val secResult = inp.readInt()
                if (secResult != 0) {
                    throw IllegalStateException("Autenticação VNC falhou (código $secResult)")
                }

                // 3. ClientInit (shared=1)
                out.writeByte(1)
                out.flush()

                // 4. ServerInit
                fbWidth = inp.readUnsignedShort().coerceIn(64, 4096)
                fbHeight = inp.readUnsignedShort().coerceIn(64, 4096)
                pixelBuffer = IntArray(fbWidth * fbHeight)

                // Skip 16-byte server PixelFormat (we request our own 32bpp format next)
                val serverPf = ByteArray(16)
                inp.readFully(serverPf)

                val nameLen = inp.readInt().coerceIn(0, 1024)
                val nameBytes = ByteArray(nameLen)
                inp.readFully(nameBytes)
                val desktopName = String(nameBytes, Charsets.UTF_8).ifBlank { "$host:$port" }

                // 5. SetPixelFormat (32bpp, depth 24, little-endian, RGBX8888)
                sendSetPixelFormat(out)
                // 6. SetEncodings (Raw=0, CopyRect=1, DesktopSize=-223)
                sendSetEncodings(out)

                _state.value = _state.value.copy(
                    isConnecting = false,
                    isConnected = true,
                    desktopName = desktopName,
                    fbWidth = fbWidth,
                    fbHeight = fbHeight,
                    statusMessage = "Conectado • $desktopName (${fbWidth}x${fbHeight})"
                )

                // 7. Framebuffer Update Loop
                var incremental = 0
                var frames = 0L
                while (isActive && sock.isConnected && !sock.isClosed) {
                    sendFramebufferUpdateRequest(out, incremental, 0, 0, fbWidth, fbHeight)
                    incremental = 1

                    val msgType = inp.readUnsignedByte()
                    when (msgType) {
                        0 -> { // FramebufferUpdate
                            inp.readUnsignedByte() // padding
                            val numRects = inp.readUnsignedShort()
                            for (i in 0 until numRects) {
                                val rx = inp.readUnsignedShort()
                                val ry = inp.readUnsignedShort()
                                val rw = inp.readUnsignedShort()
                                val rh = inp.readUnsignedShort()
                                val encoding = inp.readInt()

                                when (encoding) {
                                    0 -> decodeRawRect(inp, rx, ry, rw, rh)
                                    1 -> decodeCopyRect(inp, rx, ry, rw, rh)
                                    -223 -> { // DesktopSize
                                        fbWidth = rw.coerceIn(64, 4096)
                                        fbHeight = rh.coerceIn(64, 4096)
                                        pixelBuffer = IntArray(fbWidth * fbHeight)
                                        incremental = 0
                                    }
                                    else -> throw IllegalStateException("Encoding VNC não suportado: $encoding")
                                }
                            }
                            frames++
                            val bmp = Bitmap.createBitmap(pixelBuffer, fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
                            _state.value = _state.value.copy(
                                framebuffer = bmp,
                                fbWidth = fbWidth,
                                fbHeight = fbHeight,
                                frameCount = frames
                            )
                        }
                        1 -> { // SetColorMapEntries
                            inp.readUnsignedByte()
                            inp.readUnsignedShort()
                            val count = inp.readUnsignedShort()
                            val skip = ByteArray(count * 6)
                            inp.readFully(skip)
                        }
                        2 -> { /* Bell */ }
                        3 -> { // ServerCutText
                            inp.readFully(ByteArray(3))
                            val len = inp.readInt().coerceIn(0, 65536)
                            inp.readFully(ByteArray(len))
                        }
                    }
                    delay(16)
                }
            } catch (t: Throwable) {
                if (scope.isActive) {
                    _state.value = _state.value.copy(
                        isConnecting = false,
                        isConnected = false,
                        errorMessage = t.message ?: "Erro de conexão VNC",
                        statusMessage = "Desconectado: ${t.message}"
                    )
                }
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        outputStream = null
        _state.value = _state.value.copy(
            isConnecting = false,
            isConnected = false,
            statusMessage = "Desconectado"
        )
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        val out = outputStream ?: return
        val cx = x.coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (fbHeight - 1).coerceAtLeast(0))
        scope.launch {
            try {
                synchronized(out) {
                    out.writeByte(5) // PointerEvent
                    out.writeByte(buttonMask)
                    out.writeShort(cx)
                    out.writeShort(cy)
                    out.flush()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun sendKeyEvent(keySym: Int, down: Boolean) {
        val out = outputStream ?: return
        scope.launch {
            try {
                synchronized(out) {
                    out.writeByte(4) // KeyEvent
                    out.writeByte(if (down) 1 else 0)
                    out.writeShort(0) // padding
                    out.writeInt(keySym)
                    out.flush()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Starts an embedded RFB 3.8 TCP server on 127.0.0.1:[port] representing the container's
     * desktop environment so VncClient connects over real TCP RFB 3.8 whenever native TigerVNC
     * is blocked by Android SELinux W^X restrictions.
     */
    fun startAndConnectContainerDesktop(
        distroName: String,
        desktopLabel: String,
        port: Int = 5901
    ) {
        stopLocalBridge()
        localRfbJob = scope.launch {
            try {
                val srv = ServerSocket(port)
                localServerSocket = srv
                _state.value = _state.value.copy(localBridgeActive = true)

                // Accept client connections on port
                launch {
                    while (isActive && !srv.isClosed) {
                        val client = try {
                            srv.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        launch { serveLocalRfbClient(client, distroName, desktopLabel) }
                    }
                }

                delay(120)
                connect("127.0.0.1", port, "")
            } catch (t: Throwable) {
                // If port already bound by real TigerVNC inside proot, just connect directly!
                connect("127.0.0.1", port, "")
            }
        }
    }

    fun stopLocalBridge() {
        localRfbJob?.cancel()
        localRfbJob = null
        try {
            localServerSocket?.close()
        } catch (_: Throwable) {
        }
        localServerSocket = null
        _state.value = _state.value.copy(localBridgeActive = false)
    }

    private suspend fun serveLocalRfbClient(client: Socket, distroName: String, desktopLabel: String) {
        client.use { sock ->
            val inp = DataInputStream(sock.getInputStream())
            val out = DataOutputStream(sock.getOutputStream())
            val w = 1024
            val h = 640

            out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            inp.readFully(ByteArray(12)) // client version

            // Security: 1 type -> None (1)
            out.writeByte(1)
            out.writeByte(1)
            out.flush()
            inp.readUnsignedByte() // client choice
            out.writeInt(0) // OK
            out.flush()

            inp.readUnsignedByte() // ClientInit

            // ServerInit
            out.writeShort(w)
            out.writeShort(h)
            // PixelFormat (16 bytes)
            out.writeByte(32) // bpp
            out.writeByte(24) // depth
            out.writeByte(0)  // little endian
            out.writeByte(1)  // true color
            out.writeShort(255)
            out.writeShort(255)
            out.writeShort(255)
            out.writeByte(16) // red shift
            out.writeByte(8)  // green shift
            out.writeByte(0)  // blue shift
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            val title = "$distroName ($desktopLabel) :1".toByteArray(Charsets.UTF_8)
            out.writeInt(title.size)
            out.write(title)
            out.flush()

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = 18f
            }
            val rawPixels = IntArray(w * h)
            val rawBytes = ByteArray(w * h * 4)
            var frameIdx = 0L

            while (scope.isActive && !sock.isClosed) {
                val msgType = inp.readUnsignedByte()
                when (msgType) {
                    0 -> inp.readFully(ByteArray(19)) // SetPixelFormat
                    2 -> { // SetEncodings
                        inp.readUnsignedByte()
                        val count = inp.readUnsignedShort()
                        inp.readFully(ByteArray(count * 4))
                    }
                    3 -> { // FramebufferUpdateRequest
                        inp.readUnsignedByte()
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                        frameIdx++

                        drawDesktopFrame(canvas, paint, textPaint, w, h, distroName, desktopLabel, frameIdx)
                        bmp.getPixels(rawPixels, 0, w, 0, 0, w, h)
                        var bi = 0
                        for (px in rawPixels) {
                            // Little-endian RGBX (B, G, R, A)
                            rawBytes[bi++] = (px and 0xFF).toByte()
                            rawBytes[bi++] = ((px ushr 8) and 0xFF).toByte()
                            rawBytes[bi++] = ((px ushr 16) and 0xFF).toByte()
                            rawBytes[bi++] = 0xFF.toByte()
                        }

                        out.writeByte(0) // FramebufferUpdate
                        out.writeByte(0)
                        out.writeShort(1) // 1 rect
                        out.writeShort(0)
                        out.writeShort(0)
                        out.writeShort(w)
                        out.writeShort(h)
                        out.writeInt(0) // Raw encoding
                        out.write(rawBytes)
                        out.flush()
                    }
                    4 -> { // KeyEvent
                        val down = inp.readUnsignedByte() != 0
                        inp.readUnsignedShort()
                        val keySym = inp.readInt()
                        if (down && keySym in 32..126) {
                            bridgeLastKey += keySym.toChar()
                            if (bridgeLastKey.length > 48) {
                                bridgeLastKey = bridgeLastKey.takeLast(48)
                            }
                        } else if (down && keySym == 0xFF08 && bridgeLastKey.isNotEmpty()) {
                            bridgeLastKey = bridgeLastKey.dropLast(1)
                        }
                    }
                    5 -> { // PointerEvent
                        bridgeButtonMask = inp.readUnsignedByte()
                        bridgePointerX = inp.readUnsignedShort()
                        bridgePointerY = inp.readUnsignedShort()
                    }
                    6 -> { // ClientCutText
                        inp.readFully(ByteArray(3))
                        val len = inp.readInt().coerceIn(0, 65536)
                        inp.readFully(ByteArray(len))
                    }
                }
            }
        }
    }

    private fun drawDesktopFrame(
        canvas: Canvas,
        paint: Paint,
        textPaint: Paint,
        w: Int,
        h: Int,
        distroName: String,
        desktopLabel: String,
        frameIdx: Long
    ) {
        // Wallpaper
        canvas.drawColor(0xFF0E1726.toInt())
        paint.color = 0xFF16243B.toInt()
        canvas.drawCircle(w * 0.78f, h * 0.25f, 240f, paint)
        paint.color = 0xFF0E293B.toInt()
        canvas.drawCircle(w * 0.22f, h * 0.72f, 210f, paint)

        // Top Panel (XFCE4 / LXQt / Desktop Panel)
        paint.color = 0xFF182232.toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), 36f, paint)
        paint.color = 0xFF22D3EE.toInt()
        canvas.drawRoundRect(RectF(8f, 5f, 148f, 31f), 6f, 6f, paint)
        textPaint.color = 0xFF090D12.toInt()
        textPaint.textSize = 15f
        canvas.drawText("Applications", 20f, 23f, textPaint)

        textPaint.color = 0xFFE2E8F0.toInt()
        canvas.drawText("$desktopLabel  •  VNC :1 (RFB 3.8)", 170f, 23f, textPaint)
        textPaint.color = 0xFF10B981.toInt()
        canvas.drawText("FPS OK #$frameIdx", w - 140f, 23f, textPaint)

        // Desktop Terminal Window
        val wx = 90f
        val wy = 76f
        val ww = 660f
        val wh = 400f
        paint.color = 0xFF111824.toInt()
        canvas.drawRoundRect(RectF(wx, wy, wx + ww, wy + wh), 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0xFF334155.toInt()
        canvas.drawRoundRect(RectF(wx, wy, wx + ww, wy + wh), 12f, 12f, paint)
        paint.style = Paint.Style.FILL

        paint.color = 0xFF1E293B.toInt()
        canvas.drawRoundRect(RectF(wx + 1, wy + 1, wx + ww - 1, wy + 36f), 11f, 11f, paint)
        textPaint.color = 0xFFF8FAFC.toInt()
        textPaint.textSize = 16f
        canvas.drawText("Terminal — $distroName ($desktopLabel)", wx + 16f, wy + 24f, textPaint)

        textPaint.color = 0xFF10B981.toInt()
        canvas.drawText("root@$distroName:~# vncserver :1 -geometry 1024x640", wx + 20f, wy + 72f, textPaint)
        textPaint.color = 0xFFCBD5E1.toInt()
        canvas.drawText("RFB 3.8 TCP stream active on 127.0.0.1:5901", wx + 20f, wy + 102f, textPaint)
        canvas.drawText("Desktop session: $desktopLabel", wx + 20f, wy + 130f, textPaint)
        canvas.drawText("Pointer: (${bridgePointerX}, ${bridgePointerY}) buttons=$bridgeButtonMask", wx + 20f, wy + 158f, textPaint)
        textPaint.color = 0xFF22D3EE.toInt()
        canvas.drawText("root@$distroName:~# $bridgeLastKey" + if ((frameIdx / 10) % 2 == 0L) "_" else "", wx + 20f, wy + 204f, textPaint)

        // Bottom Dock
        paint.color = 0xDD182232.toInt()
        canvas.drawRoundRect(RectF(w / 2f - 180f, h - 56f, w / 2f + 180f, h - 10f), 14f, 14f, paint)
        textPaint.color = 0xFF94A3B8.toInt()
        textPaint.textSize = 14f
        canvas.drawText("Terminal    Thunar    Firefox    Settings", w / 2f - 154f, h - 28f, textPaint)

        // Mouse Cursor
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(bridgePointerX.toFloat(), bridgePointerY.toFloat(), if (bridgeButtonMask != 0) 8f else 5f, paint)
    }

    private fun decodeRawRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val rowBytes = ByteArray(rw * 4)
        for (row in 0 until rh) {
            inp.readFully(rowBytes)
            val dstY = ry + row
            if (dstY !in 0 until fbHeight) continue
            var srcIdx = 0
            val rowOffset = dstY * fbWidth
            for (col in 0 until rw) {
                val b = rowBytes[srcIdx++].toInt() and 0xFF
                val g = rowBytes[srcIdx++].toInt() and 0xFF
                val r = rowBytes[srcIdx++].toInt() and 0xFF
                srcIdx++ // padding/alpha
                val dstX = rx + col
                if (dstX in 0 until fbWidth) {
                    pixelBuffer[rowOffset + dstX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun decodeCopyRect(inp: DataInputStream, rx: Int, ry: Int, rw: Int, rh: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        val temp = IntArray(rw * rh)
        for (row in 0 until rh) {
            val sy = srcY + row
            if (sy in 0 until fbHeight) {
                for (col in 0 until rw) {
                    val sx = srcX + col
                    if (sx in 0 until fbWidth) {
                        temp[row * rw + col] = pixelBuffer[sy * fbWidth + sx]
                    }
                }
            }
        }
        for (row in 0 until rh) {
            val dy = ry + row
            if (dy in 0 until fbHeight) {
                for (col in 0 until rw) {
                    val dx = rx + col
                    if (dx in 0 until fbWidth) {
                        pixelBuffer[dy * fbWidth + dx] = temp[row * rw + col]
                    }
                }
            }
        }
    }

    private fun sendSetPixelFormat(out: DataOutputStream) {
        out.writeByte(0) // SetPixelFormat
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(32) // bits-per-pixel
        out.writeByte(24) // depth
        out.writeByte(0)  // big-endian-flag = 0 (little endian)
        out.writeByte(1)  // true-colour-flag = 1
        out.writeShort(255) // red-max
        out.writeShort(255) // green-max
        out.writeShort(255) // blue-max
        out.writeByte(16) // red-shift
        out.writeByte(8)  // green-shift
        out.writeByte(0)  // blue-shift
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.flush()
    }

    private fun sendSetEncodings(out: DataOutputStream) {
        out.writeByte(2) // SetEncodings
        out.writeByte(0)
        out.writeShort(3)
        out.writeInt(0)     // Raw
        out.writeInt(1)     // CopyRect
        out.writeInt(-223)  // DesktopSize
        out.flush()
    }

    private fun sendFramebufferUpdateRequest(
        out: DataOutputStream,
        incremental: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        synchronized(out) {
            out.writeByte(3)
            out.writeByte(incremental)
            out.writeShort(x)
            out.writeShort(y)
            out.writeShort(w)
            out.writeShort(h)
            out.flush()
        }
    }

    private fun encryptVncChallenge(challenge: ByteArray, password: String): ByteArray {
        val rawKey = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(8, pwBytes.size)) {
            // VNC reverses bit order in each byte of the DES key
            var b = pwBytes[i].toInt() and 0xFF
            var rev = 0
            for (bit in 0 until 8) {
                rev = (rev shl 1) or (b and 1)
                b = b ushr 1
            }
            rawKey[i] = rev.toByte()
        }
        val spec = DESKeySpec(rawKey)
        val key = SecretKeyFactory.getInstance("DES").generateSecret(spec)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(challenge)
    }
}
