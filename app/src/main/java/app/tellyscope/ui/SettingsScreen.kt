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
import androidx.compose.material3.Checkbox
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
    onExportBackup: ((CharArray) -> Unit)? = null,
    onImportBackup: ((CharArray) -> Unit)? = null,
    onExportAdbIdentity: ((CharArray) -> Unit)? = null,
    backupEntries: Set<String> = emptySet(),
    onRestoreBackup: (Set<String>) -> Unit = {},
    onCancelBackupRestore: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var backupPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupVisible by rememberSaveable { mutableStateOf(false) }
    var backupMode by rememberSaveable { mutableStateOf("export") }
    var checkedSections by remember { mutableStateOf(emptySet<String>()) }


    var advanced by rememberSaveable { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    val open = { url: String -> runCatching { browser.openUri(url) }; Unit }
    if (editing) SourceDialog(listSource, onSave = { editing = false; onListSource(it) }, onDismiss = { editing = false })
    if (backupPasswordDialog) {
        AlertDialog(
            onDismissRequest = { backupPassword = ""; backupPasswordDialog = false },
            title = { Text(if (backupMode == "adb") "Экспорт личного ключа ADB" else "Зашифрованная резервная копия") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (backupMode) {
                        "adb" -> "Внимание: ключ ADB даёт возможность авторизоваться на сопряжённых телевизорах. Экспортируйте его только лично, храните отдельно от пароля и никогда не отправляйте в чат или GitHub."
                        "export" -> "Сохраняются локальные сведения об устройствах, история и настройки. Ключи ADB в копию не включаются."
                        else -> "Выберите зашифрованный файл и введите его пароль. После проверки вы сможете выбрать разделы восстановления."
                    })
                    Text(if (backupMode == "import") "Введите пароль, с которым файл был создан."
                    else if (backupMode == "adb") "Используйте новый ОТДЕЛЬНЫЙ пароль от 12 символов, не пароль обычных резервных копий."
                    else "Придумайте отдельный пароль длиной не менее 12 символов и храните его надёжно.")
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Пароль копии") },
                        visualTransformation = if (backupVisible) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = backupVisible, onCheckedChange = { backupVisible = it })
                        Text("Показывать пароль", Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = if (backupMode == "import") backupPassword.isNotEmpty() else backupPassword.length >= 12,
                    onClick = {
                        val password = backupPassword.toCharArray()
                        backupPassword = ""
                        backupPasswordDialog = false
                        when (backupMode) {
                            "export" -> onExportBackup?.invoke(password)
                            "adb" -> onExportAdbIdentity?.invoke(password)
                            else -> onImportBackup?.invoke(password)
                        }
                    },
                ) { Text(if (backupMode == "import") "Выбрать файл копии" else "Выбрать место сохранения") }
            },
            dismissButton = {
                TextButton(onClick = { backupPassword = ""; backupPasswordDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (backupEntries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { checkedSections = emptySet(); onCancelBackupRestore() },
            title = { Text("Выберите разделы восстановления") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Восстановление заменяет выбранные локальные данные. Для применения потребуется перезапуск приложения. Ключи ADB не импортируются.")
                    backupEntries.sorted().forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                checkedSections = if (name in checkedSections) checkedSections - name else checkedSections + name
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = name in checkedSections, onCheckedChange = null)
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = checkedSections.isNotEmpty(),
                    onClick = {
                        val chosen = checkedSections
                        checkedSections = emptySet()
                        onRestoreBackup(chosen)
                    },
                ) { Text("Восстановить выбранное") }
            },
            dismissButton = {
                TextButton(onClick = {
                    checkedSections = emptySet()
                    onCancelBackupRestore()
                }) { Text("Отмена") }
            },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Вернуться к устройству") }
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
            if (onExportBackup != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Локальная резервная копия", style = MaterialTheme.typography.titleMedium)
                        Text("Парольный экспорт истории, сведений об устройствах и настроек. Без облака и без ключей ADB.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { backupMode = "export"; backupPassword = ""; backupPasswordDialog = true }) {
                            Text("Создать зашифрованную копию")
                        }
                        if (onImportBackup != null) {
                            TextButton(onClick = {
                                backupMode = "import"
                                checkedSections = emptySet()
                                backupPassword = ""
                                backupPasswordDialog = true
                            }) { Text("Восстановить из зашифрованной копии") }
                        }
                        if (onExportAdbIdentity != null) {
                            TextButton(onClick = {
                                backupMode = "adb"
                                backupPassword = ""
                                backupVisible = false
                                backupPasswordDialog = true
                            }) { Text("Отдельно экспортировать ключ ADB (с предупреждением)") }
                        }
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
