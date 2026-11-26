package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TerminalLine
import com.example.engine.TerminalUiState
import com.example.model.InstalledContainer
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldPhosphor
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun TerminalScreen(
    terminalState: TerminalUiState,
    containers: List<InstalledContainer>,
    activeContainer: InstalledContainer?,
    fontSizeSp: Int,
    onSelectContainer: (InstalledContainer) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmitCommand: (String?) -> Unit,
    onSpecialKey: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onClear: () -> Unit,
    onAdjustFontSize: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showContainerMenu by remember { mutableStateOf(false) }

    LaunchedEffect(terminalState.lines.size) {
        if (terminalState.lines.isNotEmpty()) {
            listState.animateScrollToItem(terminalState.lines.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06090D))
            .imePadding()
    ) {
        // Terminal Top Toolbar
        Surface(
            color = Color(0xFF0E141D),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    Surface(
                        color = Color(0xFF17202E),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clickable(enabled = containers.isNotEmpty()) { showContainerMenu = true }
                            .testTag("terminal_container_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (terminalState.isRunning) EmeraldPhosphor else Color(0xFFF59E0B))
                            )
                            Text(
                                text = activeContainer?.name ?: "Sem container",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showContainerMenu,
                        onDismissRequest = { showContainerMenu = false }
                    ) {
                        containers.forEach { container ->
                            DropdownMenuItem(
                                text = { Text("${container.name} (${container.arch.lxcArch})") },
                                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                onClick = {
                                    onSelectContainer(container)
                                    showContainerMenu = false
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onAdjustFontSize(-1) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Diminuir fonte", tint = Color(0xFF94A3B8))
                    }
                    Text(
                        text = "${fontSizeSp}sp",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    IconButton(onClick = { onAdjustFontSize(1) }) {
                        Icon(Icons.Default.Add, contentDescription = "Aumentar fonte", tint = Color(0xFF94A3B8))
                    }
                    IconButton(
                        onClick = {
                            val text = terminalState.lines.joinToString("\n") { line ->
                                line.segments.joinToString("") { it.text }
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ProotBox Terminal", text))
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar terminal", tint = Color(0xFF94A3B8))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Limpar terminal", tint = Color(0xFF94A3B8))
                    }
                }
            }
        }

        // Execution mode banner
        Surface(color = Color(0xFF0A0F16), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${terminalState.executionModeLabel} • cwd: ${terminalState.workingDirVirtual}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = CyanElectric.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // ANSI Output Buffer
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(terminalState.lines) { line ->
                TerminalLineRow(line = line, fontSizeSp = fontSizeSp)
            }
        }

        // Quick-Command Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0F16))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "neofetch",
                "ls -la /",
                "cat /etc/os-release",
                "wayland-info",
                "start-wayland",
                "start-vnc",
                "pkg-list",
                "uname -a"
            ).forEach { cmd ->
                AssistChip(
                    onClick = { onSubmitCommand(cmd) },
                    label = {
                        Text(
                            text = cmd,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                )
            }
        }

        // Command Input Line
        Surface(
            color = Color(0xFF0E141D),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = terminalState.promptText,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldPhosphor
                )
                BasicTextField(
                    value = terminalState.currentInputLine,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = fontSizeSp.sp,
                        color = Color.White
                    ),
                    cursorBrush = SolidColor(CyanElectric),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmitCommand(null) }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terminal_command_input")
                )
                IconButton(
                    onClick = { onSubmitCommand(null) },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("terminal_send_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Executar comando",
                        tint = CyanElectric,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Extra Keys Toolbar (Termux-style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111824))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExtraKeyButton("ESC") { onSpecialKey("ESC") }
            ExtraKeyButton("TAB") { onSpecialKey("TAB") }
            ExtraKeyButton("CTRL", active = terminalState.ctrlActive, onClick = onToggleCtrl)
            ExtraKeyButton("ALT", active = terminalState.altActive, onClick = onToggleAlt)
            ExtraKeyButton("CTRL+C") { onSpecialKey("CTRL_C") }
            ExtraKeyButton("CTRL+L") { onSpecialKey("CTRL_L") }
            ExtraKeyButton("↑") { onSpecialKey("UP") }
            ExtraKeyButton("↓") { onSpecialKey("DOWN") }
            ExtraKeyButton("←") { onSpecialKey("LEFT") }
            ExtraKeyButton("→") { onSpecialKey("RIGHT") }
            ExtraKeyButton("/") { onSpecialKey("/") }
            ExtraKeyButton("-") { onSpecialKey("-") }
            ExtraKeyButton("|") { onSpecialKey("|") }
            ExtraKeyButton("~") { onSpecialKey("~") }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine, fontSizeSp: Int) {
    val annotated = remember(line) {
        buildAnnotatedString {
            for (seg in line.segments) {
                pushStyle(
                    SpanStyle(
                        color = seg.fg,
                        background = seg.bg ?: Color.Transparent,
                        fontWeight = if (seg.bold) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (seg.underline) TextDecoration.Underline else TextDecoration.None
                    )
                )
                append(seg.text)
                pop()
            }
        }
    }
    Text(
        text = annotated,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 4).sp
    )
}

@Composable
private fun ExtraKeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) CyanElectric.copy(alpha = 0.25f) else Color(0xFF1C2636),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) CyanElectric else Color(0xFF2D3A4F)),
        modifier = Modifier
            .clickable(onClick = onClick)
            .height(34.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) CyanElectric else Color(0xFFE2E8F0)
            )
        }
    }
}
