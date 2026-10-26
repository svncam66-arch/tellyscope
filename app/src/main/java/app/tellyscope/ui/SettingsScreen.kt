// SPDX-License-Identifier: GPL-3.0-only
// The open-in-browser icon is the Material Icons "open_in_new" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tellyscope.data.RELEASES
import app.tellyscope.data.sourceName

/** Where the lists come from, credited in Settings. Its star count is written in, not fetched. */
internal const val CLEANYOURTV = "https://github.com/Gathin23/cleanyourtv"

/** CONTRIBUTING's guide to pointing the app at your own lists. */
internal val OWN_LISTS_GUIDE = RELEASES.removeSuffix("/releases") + "/blob/main/CONTRIBUTING.md#using-your-own-lists"

/**
 * App-wide choices, not tied to one TV: update checks first, then a folded Advanced with where the
 * debloat lists come from and how to use your own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The user's own debloat-list source; null for tellyscope-devices. */
    listSource: String?,
    onListSource: (String?) -> Unit,
    /** Null hides the switch: this build doesn't check (screenshots). */
    checkUpdates: Boolean?,
    /** Alpha builds check every 5 minutes, release builds every 30 minutes. */
    alphaBuild: Boolean,
    onCheckUpdates: (Boolean) -> Unit,
    back: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    val open = { url: String -> runCatching { browser.openUri(url) }; Unit }
    if (editing) SourceDialog(listSource, onSave = { editing = false; onListSource(it) }, onDismiss = { editing = false })
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the TV's page") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (checkUpdates != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(checkUpdates, role = Role.Switch, onValueChange = onCheckUpdates)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Проверять обновления", style = MaterialTheme.typography.titleSmall)
                            Text(
                                (if (alphaBuild) "Каждые 5 минут в экспериментальных сборках" else "Каждые 30 минут") +
                                    ", проверять наличие новой версии на GitHub. Автоматическая загрузка не выполняется.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(checked = checkUpdates, onCheckedChange = null)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(advanced, role = Role.Button, onValueChange = { advanced = it })
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Дополнительно", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (advanced) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    ListsFrom(listSource, onOpen = { open(CLEANYOURTV) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Изменить источник", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Укажите HTTPS-адрес каталога с index.json, например raw.githubusercontent.com/<you>/tellyscope-devices/main/",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.clickable { open(OWN_LISTS_GUIDE) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Как использовать свои списки", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(OpenInNew, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(Modifier.fillMaxWidth().clickable { editing = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Собственные списки оптимизации", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * Where the lists come from: cleanyourtv with its stars, opening its GitHub page, or the user's own
 * source by name once they've set one.
 */
@Composable
private fun ListsFrom(source: String?, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (source == null) Modifier.clickable(onClick = onOpen) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Источник рекомендаций", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (source == null) "cleanyourtv" else sourceName(source), style = MaterialTheme.typography.titleMedium)
                if (source == null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Star, contentDescription = null, Modifier.size(16.dp), tint = Color(0xFFE0A100))
                    Text(" 30k+", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }
        if (source == null) Icon(OpenInNew, contentDescription = "Открыть cleanyourtv на GitHub", tint = c.onSurfaceVariant)
    }
}

private val OpenInNew = ImageVector.Builder("OpenInNew", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z").toNodes(),
        pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black),
    )
    .build()

/** Edits the list source: an HTTPS folder holding `index.json`, or back to the default. */
@Composable
private fun SourceDialog(source: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(source.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Источник списков рекомендаций") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                     "Чтобы использовать свои списки, создайте ответвление tellyscope-devices и укажите адрес каталога с index.json, " +
                        "например https://raw.githubusercontent.com/you/tellyscope-devices/main/. Системная защита действует независимо от рекомендаций.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(url, { url = it }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (source != null) TextButton(onClick = { onSave(null) }) { Text("Вернуть стандартный источник") }
            }
        },
        confirmButton = { Button(onClick = { onSave(url) }, enabled = url.isNotBlank()) { Text("Использовать списки") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
