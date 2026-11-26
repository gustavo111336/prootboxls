package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.engine.VncUiState
import com.example.engine.WaylandState
import com.example.model.InstalledContainer
import com.example.ui.DisplaySubTab
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldPhosphor
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.VioletWayland

@Composable
fun DisplayScreen(
    subTab: DisplaySubTab,
    waylandState: WaylandState,
    vncState: VncUiState,
    activeContainer: InstalledContainer?,
    onSelectSubTab: (DisplaySubTab) -> Unit,
    onStartWayland: () -> Unit,
    onStopWayland: () -> Unit,
    onTestWaylandClient: () -> Unit,
    onWaylandPointer: (Float, Float, Boolean) -> Unit,
    onWaylandKey: (String) -> Unit,
    onVncConnect: (String, Int, String) -> Unit,
    onVncDisconnect: () -> Unit,
    onLaunchContainerVnc: () -> Unit,
    onVncPointer: (Int, Int, Int) -> Unit,
    onVncKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 800.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("display_mode_switcher")
            ) {
                SegmentedButton(
                    selected = subTab == DisplaySubTab.WAYLAND,
                    onClick = { onSelectSubTab(DisplaySubTab.WAYLAND) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) }
                ) {
                    Text("Wayland Real")
                }
                SegmentedButton(
                    selected = subTab == DisplaySubTab.VNC,
                    onClick = { onSelectSubTab(DisplaySubTab.VNC) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp)) }
                ) {
                    Text("VNC Integrado")
                }
            }

            if (subTab == DisplaySubTab.WAYLAND) {
                WaylandPanel(
                    state = waylandState,
                    activeContainer = activeContainer,
                    onStart = onStartWayland,
                    onStop = onStopWayland,
                    onTestClient = onTestWaylandClient,
                    onPointer = onWaylandPointer,
                    onKey = onWaylandKey
                )
            } else {
                VncPanel(
                    state = vncState,
                    activeContainer = activeContainer,
                    onConnect = onVncConnect,
                    onDisconnect = onVncDisconnect,
                    onLaunchContainerDesktop = onLaunchContainerVnc,
                    onPointer = onVncPointer,
                    onKey = onVncKey
                )
            }
        }
    }
}

@Composable
private fun WaylandPanel(
    state: WaylandState,
    activeContainer: InstalledContainer?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestClient: () -> Unit,
    onPointer: (Float, Float, Boolean) -> Unit,
    onKey: (String) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize(960, 600)) }
    var keyInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (state.isRunning) VioletWayland.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state.isRunning) VioletWayland else Color.Gray)
                    )
                    Column {
                        Text("Compositor Wayland (${state.displayName})", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${state.transportMode} • ${state.connectedClients} clientes • ${state.frameCount} frames",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!state.isRunning) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_wayland_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Iniciar Compositor")
                    }
                } else {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Parar")
                    }
                }

                FilledTonalButton(
                    onClick = onTestClient,
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("test_wayland_client_button")
                ) {
                    Text("Abrir Sessão Wayland")
                }
            }
        }
    }

    // Wayland Interactive Surface Viewport
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070A0F)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        val bmp = state.surfaceBitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .onSizeChanged { canvasSize = it }
                .pointerInput(state.width, state.height) {
                    detectTapGestures { offset ->
                        val wx = (offset.x / canvasSize.width.coerceAtLeast(1)) * state.width
                        val wy = (offset.y / canvasSize.height.coerceAtLeast(1)) * state.height
                        onPointer(wx, wy, true)
                        onPointer(wx, wy, false)
                    }
                }
                .pointerInput(state.width, state.height) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val wx = (offset.x / canvasSize.width.coerceAtLeast(1)) * state.width
                            val wy = (offset.y / canvasSize.height.coerceAtLeast(1)) * state.height
                            onPointer(wx, wy, true)
                        },
                        onDragEnd = {
                            onPointer(0f, 0f, false)
                        },
                        onDragCancel = {
                            onPointer(0f, 0f, false)
                        },
                        onDrag = { change, _ ->
                            val wx = (change.position.x / canvasSize.width.coerceAtLeast(1)) * state.width
                            val wy = (change.position.y / canvasSize.height.coerceAtLeast(1)) * state.height
                            onPointer(wx, wy, true)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Superfície Wayland wl_shm",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        tint = VioletWayland,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Compositor Wayland aguardando superfície wl_surface",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Exporte WAYLAND_DISPLAY=${state.displayName} no terminal ou toque em 'Abrir Sessão Wayland' para testar o protocolo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }

    // Wayland Keyboard Forwarding Input
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            placeholder = { Text("Enviar teclado wl_keyboard (${activeContainer?.name ?: "Wayland"})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (keyInput.isNotBlank()) {
                        onKey(keyInput)
                        keyInput = ""
                    }
                }
            ),
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = {
                if (keyInput.isNotBlank()) {
                    onKey(keyInput)
                    keyInput = ""
                }
            }
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar tecla Wayland")
        }
    }

    // Wayland Protocol Inspector
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Globais Anunciados (wl_registry)", style = MaterialTheme.typography.labelLarge, color = VioletWayland)
            Text(
                text = state.advertisedGlobals.joinToString("  •  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.protocolLogs.isNotEmpty()) {
                Surface(
                    color = Color(0xFF070A0E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        state.protocolLogs.takeLast(8).forEach { log ->
                            Text(
                                text = log,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VncPanel(
    state: VncUiState,
    activeContainer: InstalledContainer?,
    onConnect: (String, Int, String) -> Unit,
    onDisconnect: () -> Unit,
    onLaunchContainerDesktop: () -> Unit,
    onPointer: (Int, Int, Int) -> Unit,
    onKey: (Int, Boolean) -> Unit
) {
    var host by remember { mutableStateOf(state.host) }
    var portText by remember { mutableStateOf(state.port.toString()) }
    var password by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var viewportSize by remember { mutableStateOf(IntSize(1024, 640)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (state.isConnected) EmeraldPhosphor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Visualizador VNC (RFB 3.8)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.statusMessage} • Frames: ${state.frameCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (state.isConnected) EmeraldPhosphor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Porta") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLaunchContainerDesktop,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("launch_vnc_desktop_button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Iniciar Desktop (${activeContainer?.distroId ?: "Linux"})", maxLines = 1)
                }

                if (!state.isConnected) {
                    FilledTonalButton(
                        onClick = {
                            onConnect(host.ifBlank { "127.0.0.1" }, portText.toIntOrNull() ?: 5901, password)
                        },
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Text("Conectar")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Text("Desconectar")
                    }
                }
            }
        }
    }

    // VNC Framebuffer Viewport
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070A0F)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        val fb = state.framebuffer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .onSizeChanged { viewportSize = it }
                .pointerInput(state.fbWidth, state.fbHeight) {
                    detectTapGestures(
                        onTap = { offset ->
                            val vx = ((offset.x / viewportSize.width.coerceAtLeast(1)) * state.fbWidth).toInt()
                            val vy = ((offset.y / viewportSize.height.coerceAtLeast(1)) * state.fbHeight).toInt()
                            onPointer(vx, vy, 1)
                            onPointer(vx, vy, 0)
                        },
                        onLongPress = { offset ->
                            val vx = ((offset.x / viewportSize.width.coerceAtLeast(1)) * state.fbWidth).toInt()
                            val vy = ((offset.y / viewportSize.height.coerceAtLeast(1)) * state.fbHeight).toInt()
                            onPointer(vx, vy, 4) // right click
                            onPointer(vx, vy, 0)
                        }
                    )
                }
                .pointerInput(state.fbWidth, state.fbHeight) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val vx = ((offset.x / viewportSize.width.coerceAtLeast(1)) * state.fbWidth).toInt()
                            val vy = ((offset.y / viewportSize.height.coerceAtLeast(1)) * state.fbHeight).toInt()
                            onPointer(vx, vy, 1)
                        },
                        onDragEnd = { onPointer(0, 0, 0) },
                        onDragCancel = { onPointer(0, 0, 0) },
                        onDrag = { change, _ ->
                            val vx = ((change.position.x / viewportSize.width.coerceAtLeast(1)) * state.fbWidth).toInt()
                            val vy = ((change.position.y / viewportSize.height.coerceAtLeast(1)) * state.fbHeight).toInt()
                            onPointer(vx, vy, 1)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (fb != null) {
                Image(
                    bitmap = fb.asImageBitmap(),
                    contentDescription = "Framebuffer VNC",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = CyanElectric,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Visualizador VNC pronto",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "Toque em 'Iniciar Desktop' para abrir a interface gráfica do container em 127.0.0.1:5901 ou conecte a qualquer servidor VNC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }

    // VNC Keyboard Input Bar
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            placeholder = { Text("Enviar texto para sessão VNC...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    keyText.forEach { ch ->
                        onKey(ch.code, true)
                        onKey(ch.code, false)
                    }
                    onKey(0xFF0D, true)
                    onKey(0xFF0D, false)
                    keyText = ""
                }
            ),
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = {
                onKey(0xFF08, true)
                onKey(0xFF08, false)
            }
        ) {
            Text("Bksp")
        }
        IconButton(
            onClick = {
                keyText.forEach { ch ->
                    onKey(ch.code, true)
                    onKey(ch.code, false)
                }
                onKey(0xFF0D, true)
                onKey(0xFF0D, false)
                keyText = ""
            }
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar texto VNC")
        }
    }
}
