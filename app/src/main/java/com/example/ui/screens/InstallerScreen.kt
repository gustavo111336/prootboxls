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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ComponentCatalog
import com.example.model.ComponentCategory
import com.example.model.DistroCatalogItem
import com.example.model.InstallJobState
import com.example.model.InstallPhase
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.EmeraldPhosphor
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun InstallerScreen(
    distroQuery: String,
    catalogMatches: List<DistroCatalogItem>,
    selectedDistro: DistroCatalogItem,
    selectedComponentIds: Set<String>,
    installJob: InstallJobState,
    onQueryChange: (String) -> Unit,
    onSelectDistro: (DistroCatalogItem) -> Unit,
    onToggleComponent: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onStartInstall: (String) -> Unit,
    onCancelInstall: () -> Unit,
    onOpenInstalledTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var customName by remember { mutableStateOf("") }

    val selectedComponents = remember(selectedComponentIds) {
        ComponentCatalog.all.filter { it.id in selectedComponentIds }
    }
    val estimatedTotalMb = remember(selectedDistro, selectedComponents) {
        selectedDistro.estimatedSizeMb + selectedComponents.sumOf {
            if (it.id == ComponentCatalog.ID_BASE_ROOTFS) 0 else it.estimatedSizeMb
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Instalador Modular",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Escolha ou digite qualquer distro e selecione exatamente o que instalar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live Install Progress & Console Logs Card
            item {
                AnimatedVisibility(visible = installJob.phase != InstallPhase.IDLE) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.5.dp,
                            when (installJob.phase) {
                                InstallPhase.COMPLETED -> EmeraldPhosphor
                                InstallPhase.ERROR -> MaterialTheme.colorScheme.error
                                else -> CyanElectric
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = installJob.phase.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = when (installJob.phase) {
                                        InstallPhase.COMPLETED -> EmeraldPhosphor
                                        InstallPhase.ERROR -> MaterialTheme.colorScheme.error
                                        else -> CyanElectric
                                    }
                                )
                                Text(
                                    text = "${(installJob.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            LinearProgressIndicator(
                                progress = { installJob.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )

                            if (installJob.detail.isNotBlank()) {
                                Text(
                                    text = installJob.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (installJob.logs.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFF070A0E),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(10.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        installJob.logs.takeLast(25).forEach { logLine ->
                                            Text(
                                                text = logLine,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFCBD5E1)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (installJob.isRunning) {
                                    OutlinedButton(
                                        onClick = onCancelInstall,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Cancelar")
                                    }
                                } else if (installJob.phase == InstallPhase.COMPLETED) {
                                    Button(
                                        onClick = onOpenInstalledTerminal,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Abrir Container no Terminal")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Distro Search / Custom Input
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
                        Text("1. Distribuição Linux ou URL RootFS", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = distroQuery,
                            onValueChange = onQueryChange,
                            label = { Text("Digite a distro (ex: ubuntu, alpine, debian:12, arch, kali, https://...)") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("installer_distro_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            catalogMatches.forEach { item ->
                                val isSelected = selectedDistro.id == item.id && selectedDistro.release == item.release
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectDistro(item) },
                                    label = { Text("${item.name} ${item.release}") },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }

                        // Selected Distro Summary Box
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${selectedDistro.name} (${selectedDistro.release})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(selectedDistro.accentHex)
                                    )
                                    Text(
                                        text = "${selectedDistro.packageManager.label}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Text(
                                    text = selectedDistro.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Fonte: ${selectedDistro.sourceLabel} • ${selectedDistro.rootfsUrl}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text("Nome do container (opcional)") },
                            placeholder = { Text("${selectedDistro.name} ${selectedDistro.release}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Granular Component Selection Presets
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "2. Selecionar Componentes e Interfaces",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Ative ou desative cada módulo individualmente antes de baixar:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "minimal" to "CLI Mínimo",
                            "wayland" to "Wayland + Sway",
                            "xfce_vnc" to "XFCE4 + VNC + Wayland",
                            "full" to "Instalar Tudo"
                        ).forEach { (key, label) ->
                            OutlinedButton(
                                onClick = { onApplyPreset(key) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // Categorized Component Checkboxes
            ComponentCategory.entries.forEach { category ->
                val itemsInCategory = ComponentCatalog.all.filter { it.category == category }
                item {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = CyanElectric,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(itemsInCategory, key = { it.id }) { component ->
                    val isChecked = component.id in selectedComponentIds
                    val pkgs = component.packagesFor(selectedDistro.packageManager)

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isChecked) CyanElectric.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !component.required) {
                                onToggleComponent(component.id)
                            }
                            .testTag("component_row_${component.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                enabled = !component.required,
                                onCheckedChange = { onToggleComponent(component.id) }
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = component.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        color = if (component.required) {
                                            EmeraldPhosphor.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (component.required) "Obrigatório" else "~${component.estimatedSizeMb} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (component.required) EmeraldPhosphor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = component.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (pkgs.isNotEmpty()) {
                                    Text(
                                        text = "${selectedDistro.packageManager.command}: ${pkgs.joinToString(" ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = CyanElectric.copy(alpha = 0.85f)
                                    )
                                } else if (component.configNote.isNotBlank()) {
                                    Text(
                                        text = component.configNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Install CTA Summary
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${selectedComponentIds.size} componentes selecionados",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Total est.: ~$estimatedTotalMb MB",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanElectric
                            )
                        }
                        Button(
                            onClick = { onStartInstall(customName) },
                            enabled = !installJob.isRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("start_full_install_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (installJob.isRunning) "Instalação em andamento..."
                                else "Baixar PRoot + RootFS e Instalar Selecionados"
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
