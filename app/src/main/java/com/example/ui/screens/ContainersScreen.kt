package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.WaylandState
import com.example.model.ComponentCatalog
import com.example.model.CpuArch
import com.example.model.InstalledContainer
import com.example.model.ProotBinaryState
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldPhosphor
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.VioletWayland

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainersScreen(
    deviceArch: CpuArch,
    prootState: ProotBinaryState,
    waylandState: WaylandState,
    containers: List<InstalledContainer>,
    activeContainer: InstalledContainer?,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onDownloadProot: () -> Unit,
    onTypeAndInstallDistro: (String) -> Unit,
    onOpenTerminal: (InstalledContainer) -> Unit,
    onLaunchWayland: (InstalledContainer) -> Unit,
    onLaunchVnc: (InstalledContainer) -> Unit,
    onUpdateComponents: (InstalledContainer, Set<String>) -> Unit,
    onDeleteContainer: (InstalledContainer) -> Unit,
    modifier: Modifier = Modifier
) {
    var typedDistro by remember { mutableStateOf("") }
    var editingComponentsFor by remember { mutableStateOf<InstalledContainer?>(null) }
    var deletingContainer by remember { mutableStateOf<InstalledContainer?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ProotBox",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Containers Linux • Wayland Real • VNC RFB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = "Arquitetura CPU",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = deviceArch.abiName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier.testTag("toggle_theme_button")
                        ) {
                            Icon(
                                imageVector = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Alternar tema"
                            )
                        }
                    }
                }
            }

            // PRoot & Wayland Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        CyanElectric.copy(alpha = 0.08f),
                                        VioletWayland.copy(alpha = 0.06f)
                                    )
                                )
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (prootState.isInstalled) EmeraldPhosphor.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.primaryContainer
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (prootState.isInstalled) Icons.Default.CheckCircle else Icons.Default.DeveloperBoard,
                                        contentDescription = "Status PRoot",
                                        tint = if (prootState.isInstalled) EmeraldPhosphor else CyanElectric
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Motor PRoot (${prootState.arch.prootArch})",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = prootState.statusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = prootState.isDownloading) {
                            LinearProgressIndicator(
                                progress = { prootState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onDownloadProot,
                                enabled = !prootState.isDownloading,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_proot_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (prootState.isInstalled) "Atualizar PRoot" else "Baixar Binários PRoot"
                                )
                            }

                            Surface(
                                color = if (waylandState.isRunning) {
                                    VioletWayland.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (waylandState.isRunning) VioletWayland else Color.Gray)
                                    )
                                    Text(
                                        text = if (waylandState.isRunning) waylandState.displayName else "Wayland off",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (waylandState.isRunning) VioletWayland else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Type any Linux Distro Quick Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Baixar Container / Distro Linux",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Digite o nome da distro (ex: ubuntu, alpine, debian:12, arch, fedora, kali) ou URL direta .tar.xz/.tar.gz para escolher componentes e rodar:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = typedDistro,
                            onValueChange = { typedDistro = it },
                            placeholder = { Text("ubuntu, alpine, archlinux, kali, fedora...") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    onTypeAndInstallDistro(typedDistro.ifBlank { "ubuntu" })
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_distro_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("alpine", "ubuntu", "debian", "archlinux", "fedora", "kali").forEach { preset ->
                                AssistChip(
                                    onClick = {
                                        typedDistro = preset
                                        onTypeAndInstallDistro(preset)
                                    },
                                    label = { Text(preset) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }

                        Button(
                            onClick = { onTypeAndInstallDistro(typedDistro.ifBlank { "ubuntu" }) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_install_distro_button")
                        ) {
                            Text("Selecionar Componentes e Baixar RootFS")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Installed Containers Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Containers Instalados (${containers.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (containers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = CyanElectric,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Nenhum container Linux instalado ainda",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Digite uma distro acima (ex: alpine ou ubuntu) para baixar o PRoot, extrair o RootFS e escolher cada componente gráfico ou CLI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(containers, key = { it.id }) { container ->
                    ContainerItemCard(
                        container = container,
                        isActive = activeContainer?.id == container.id,
                        onOpenTerminal = { onOpenTerminal(container) },
                        onLaunchWayland = { onLaunchWayland(container) },
                        onLaunchVnc = { onLaunchVnc(container) },
                        onEditComponents = { editingComponentsFor = container },
                        onDelete = { deletingContainer = container }
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Granular component editor for an existing container
    editingComponentsFor?.let { target ->
        var draftSelection by remember(target.id) { mutableStateOf(target.installedComponents) }
        ModalBottomSheet(onDismissRequest = { editingComponentsFor = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Componentes de ${target.name}",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Marque ou desmarque exatamente o que deseja manter configurado neste container:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ComponentCatalog.all, key = { it.id }) { comp ->
                        val checked = comp.id in draftSelection
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !comp.required) {
                                    draftSelection = if (checked) draftSelection - comp.id else draftSelection + comp.id
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(comp.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${comp.category.title} • ~${comp.estimatedSizeMb} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = checked,
                                    enabled = !comp.required,
                                    onCheckedChange = { isOn ->
                                        draftSelection = if (isOn) draftSelection + comp.id else draftSelection - comp.id
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { editingComponentsFor = null },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            onUpdateComponents(target, draftSelection)
                            editingComponentsFor = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Salvar Seleção")
                    }
                }
            }
        }
    }

    deletingContainer?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingContainer = null },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text("Remover container?") },
            text = { Text("Isso apagará todo o RootFS de ${target.name} (${target.sizeBytes / (1024 * 1024)} MB) do dispositivo.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDeleteContainer(target)
                        deletingContainer = null
                    }
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingContainer = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun ContainerItemCard(
    container: InstalledContainer,
    isActive: Boolean,
    onOpenTerminal: () -> Unit,
    onLaunchWayland: () -> Unit,
    onLaunchVnc: () -> Unit,
    onEditComponents: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = Color(container.accentHex)
    val sizeMb = (container.sizeBytes / (1024 * 1024)).coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("container_card_${container.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) accent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = container.distroId.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = container.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isActive) {
                                Surface(
                                    color = EmeraldPhosphor.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "ATIVO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldPhosphor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${container.arch.lxcArch} • ${container.packageManager.label} • $sizeMb MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEditComponents) {
                        Icon(Icons.Default.Tune, contentDescription = "Configurar componentes")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Remover container",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Installed component pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ComponentCatalog.all
                    .filter { it.id in container.installedComponents }
                    .forEach { comp ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = comp.name.substringBefore(" ("),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenTerminal,
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Terminal", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onLaunchWayland,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Wayland", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onLaunchVnc,
                    modifier = Modifier.weight(0.9f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("VNC", maxLines = 1)
                }
            }
        }
    }
}
