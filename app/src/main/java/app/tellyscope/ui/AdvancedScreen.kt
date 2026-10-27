// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellyscope.data.AppInfo
import app.tellyscope.data.AppNames
import app.tellyscope.data.DeviceState
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.Proc
import app.tellyscope.data.Snapshot
import app.tellyscope.data.MeasuredMetric
import app.tellyscope.data.PerformanceRun
import app.tellyscope.data.PerformanceLab
import java.util.Locale
import app.tellyscope.ui.theme.SmallNumber
import app.tellyscope.ui.theme.StatNumber

/**
 * Everything with a number in it, kept off the TV's page: the full stats, CPU and RAM by app,
 * processes, network, and the TV's address details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    state: UiState,
    back: () -> Unit,
    onExportReport: () -> Unit = {},
    onMeasure: (Boolean) -> Unit = {},
    onImportProfile: () -> Unit = {},
    onRollbackProfile: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val device = state.selected ?: return
    val ui = state.device
    val snap = ui.snapshot
    val offline = device.state != DeviceState.Online
    val labels = ui.apps.associate { it.packageName to it.label }
    fun label(p: Proc) = p.packageName?.let { labels[it] ?: AppNames.fallback(it) } ?: Copy.ANDROID_SYSTEM
    val byApp = snap?.procs.orEmpty().groupBy(::label)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                actions = { TextButton(onClick = onExportReport) { Text("Сохранить отчёт") } },
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
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Устройство", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        Text("Определение прошивки — только чтение", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ui.capabilities?.let { capabilities ->
                            KeyValue("Модель по ADB", capabilities.model ?: "Не определена")
                            KeyValue("Android API", capabilities.sdk?.toString() ?: "Не определён")
                            KeyValue("Номер сборки", capabilities.buildId ?: "Не определён")
                            KeyValue("Точная прошивка", if (capabilities.exactFirmwareKnown) "Определена, совместимость не проверена" else "Не определена")
                        } ?: KeyValue("Возможности устройства", "Не проверены")
                        KeyValue("Профиль рекомендаций", ui.signedProfileLabel ?: "Не подтверждён")
                        Text(
                            "Цифровая подпись сама по себе не подтверждает безопасность. " +
                                "Требуется проверка владельцем проекта и точное совпадение прошивки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onImportProfile, enabled = !offline) {
                            Text("Импорт подписанного профиля")
                        }
                        TextButton(
                            onClick = onRollbackProfile,
                            enabled = !offline && ui.signedProfileLabel != null,
                        ) { Text("Откатить профиль к предыдущей проверенной версии") }
                        listOfNotNull(
                            "IP-адрес" to device.hit.ip,
                            "Порт" to device.hit.port.toString(),
                            (device.manufacturer ?: device.hit.maker)?.let { "Производитель" to it },
                            device.hit.hostname?.let { "Имя в сети" to it },
                        ).forEach { (k, v) -> KeyValue(k, v) }
                    }
                }
            }
            if (snap != null) {
                // Offline: the last stats this phone saw, greyed and dated, rather than a grid of blanks.
                item { Stats(snap, if (offline) state.now else null) }
            } else {
                item {
                    Text(
                        if (offline) "Устройство не в сети. Статистика появится после подключения." else "Загрузка статистики…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { LabBlock(ui.labBefore, ui.labAfter, ui.labBusy, !offline, onMeasure) }
            // A TV that lists no processes gets no charts: no bars beats an empty card.
            if (byApp.isNotEmpty()) {
                item { UsageCard("CPU по приложениям", byApp.map { (n, ps) -> n to ps.sumOf { it.cpuPercent } }, fmt = { "%.0f%%".format(it) }) }
                item { UsageCard("Память по приложениям", byApp.map { (n, ps) -> n to ps.sumOf { it.ramMb }.toDouble() }, fmt = { Copy.mb(it.toInt()) }) }
                item { Block("Процессы") { ProcessTable(snap?.procs.orEmpty(), ui.apps) } }
            }
            ui.network?.let { n -> item { Block("Сеть") { NetworkBlock(n) } } }
        }
    }
}

@Composable
private fun LabBlock(
    before: PerformanceRun?,
    after: PerformanceRun?,
    busy: Boolean,
    online: Boolean,
    onMeasure: (Boolean) -> Unit,
) {
    Block("Лаборатория измерений") {
        Text(
            "Сравнение шести последовательных реальных показаний ADB. " +
                "Условия нагрузки должны быть одинаковыми; снижение CPU само по себе не доказывает ускорение ТВ.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onMeasure(true) }, enabled = online && !busy) {
                Text("Измерить до")
            }
            Button(onClick = { onMeasure(false) }, enabled = online && !busy && before != null) {
                Text("Измерить после")
            }
        }
        if (busy) Text("Сбор реальных показаний…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (before != null) LabNumbers("До изменений", before)
        if (after != null) LabNumbers("После изменений", after)
        if (before != null && after != null) {
            fun delta(a: MeasuredMetric?, b: MeasuredMetric?, unit: String): String {
                val change = PerformanceLab.change(a, b)
                return if (change == null) "нет сопоставимых измерений"
                    else String.format(Locale.ROOT, "%+.1f %s", change, unit)
            }
            Text("Разность «после − до» (не оценка скорости)", style = MaterialTheme.typography.titleSmall)
            Text("CPU: " + delta(before.cpuPercent, after.cpuPercent, "п.п."))
            Text("Оперативная память: " + delta(before.ramUsedMb, after.ramUsedMb, "МБ"))
            Text("Накопитель: " + delta(before.storageUsedGb, after.storageUsedGb, "ГБ"))
        }
    }
}

@Composable
private fun LabNumbers(label: String, run: PerformanceRun) {
    fun metric(m: MeasuredMetric?, unit: String): String = if (m == null) "нет данных"
        else String.format(Locale.ROOT, "%.1f ± %.1f %s (n=%d)", m.average, m.standardDeviation, unit, m.n)
    Text("$label · ${run.observations} измерений", style = MaterialTheme.typography.titleSmall)
    Text("CPU: " + metric(run.cpuPercent, "%"))
    Text("Оперативная память: " + metric(run.ramUsedMb, "МБ"))
    Text("Накопитель: " + metric(run.storageUsedGb, "ГБ"))
    Text("Температура: " + metric(run.temperatureC, "°C"))
}

@Composable
private fun Block(title: String, body: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            body()
        }
    }
}

/**
 * Memory and storage as "75% used" with a bar, then the small stats. Missing stats show "—",
 * never 0. [staleSince] set means the TV is offline: these are its last numbers, greyed and dated.
 */
@Composable
private fun Stats(s: Snapshot, staleSince: Long?) {
    val cells = listOf(
        "CPU" to if (s.cpuAvailable) "${s.cpuPercent}%" else null,
        "Температура" to s.tempC?.let { "$it°C" },
        "Время работы" to if (s.uptimeAvailable) Copy.uptime(s.uptimeSeconds) else null,
        "Экран" to s.screenOn?.let { if (it) "Вкл." else "Выкл." },
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (staleSince != null) {
                Text(
                    "На момент: ${Copy.ago(s.at, staleSince)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.alpha(if (staleSince != null) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.ramAvailable) Meter("Оперативная память", s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0)
                s.storageUsedGb?.let { u -> s.storageTotalGb?.let { t -> Meter("Накопитель", u, t) } }
                Row(Modifier.fillMaxWidth()) {
                    cells.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Meter(label: String, usedGb: Double, totalGb: Double) {
    val frac = if (totalGb > 0) (usedGb / totalGb).toFloat().coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(frac * 100).toInt()}% занято", style = StatNumber)
            }
            Text(Copy.gbOf(usedGb, totalGb), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { frac },
            Modifier.fillMaxWidth(),
            color = if (frac >= Copy.FULL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Grey bars, no chart library: top 6 by value, the rest summed as "other". */
@Composable
private fun UsageCard(title: String, rows: List<Pair<String, Double>>, fmt: (Double) -> String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Bars(rows, fmt)
        }
    }
}

@Composable
private fun Bars(rows: List<Pair<String, Double>>, fmt: (Double) -> String, top: Int = 6) {
    val sorted = rows.sortedByDescending { it.second }
    val rest = sorted.drop(top).sumOf { it.second }
    val shown = sorted.take(top) + if (rest > 0) listOf("Прочее" to rest) else emptyList()
    val max = shown.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    shown.forEach { (name, v) ->
        val frac by animateFloatAsState((v / max).toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "bar")
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Long app names wrap to two lines rather than cut off; the value column only needs "1.2 GB".
            Text(name, Modifier.width(148.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(12.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary))
            }
            Text(fmt(v), Modifier.width(60.dp), style = SmallNumber, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ProcessTable(procs: List<Proc>, apps: List<AppInfo>) {
    val labels = apps.associate { it.packageName to it.label }
    val groups = procs
        .groupBy { p -> p.packageName?.let { labels[it] ?: AppNames.fallback(it) } ?: Copy.ANDROID_SYSTEM }
        .toList()
        .sortedByDescending { (_, ps) -> ps.sumOf { it.cpuPercent } }
    if (groups.isEmpty()) return EmptyLine("")
    Row {
        Text("Приложение", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("CPU", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        Text("RAM", Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
    groups.forEach { (name, ps) ->
        var open by rememberSaveable(name) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
            Row {
                Text(
                    if (ps.size > 1) "$name  ×${ps.size}" else name,
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("%.0f%%".format(ps.sumOf { it.cpuPercent }), Modifier.width(56.dp), style = SmallNumber, textAlign = TextAlign.End)
                Text(Copy.mb(ps.sumOf { it.ramMb }), Modifier.width(72.dp), style = SmallNumber, textAlign = TextAlign.End)
            }
            if (open) ps.forEach { p ->
                Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                    Text("${p.pid}  ${p.name}", Modifier.weight(1f), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.1f%%".format(p.cpuPercent), Modifier.width(56.dp), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text(Copy.mb(p.ramMb), Modifier.width(72.dp), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun NetworkBlock(n: NetworkInfo) {
    // Already the technical screen: every fact shows, nothing behind a Details tap.
    listOfNotNull(
        "Подключение" to listOfNotNull(if (n.iface == "wifi" || n.iface.startsWith("wlan")) "Wi-Fi" else "Ethernet", n.ssid).joinToString(" · "),
        n.rssi?.let { "Сигнал" to "${signalWord(it).replaceFirstChar(Char::uppercase)} ($it dBm)" },
        "IP-адрес" to n.ip,
        n.mac?.let { "MAC" to it },
        n.linkMbps?.let { "Скорость соединения" to "$it Mbps" },
    ).forEach { (k, v) -> KeyValue(k, v) }
    Spacer(Modifier.height(8.dp))
    Text("Использование данных", style = MaterialTheme.typography.titleSmall)
    Bars(n.traffic.map { it.app to (it.rxMb + it.txMb).toDouble() }, fmt = { Copy.mb(it.toInt()) })
    Spacer(Modifier.height(8.dp))
    Text("Активные соединения", style = MaterialTheme.typography.titleSmall)
    if (n.sockets.isEmpty()) EmptyLine("")
    n.sockets.forEach { s ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(s.app, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.remote, style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun signalWord(rssi: Int) = when {
    rssi >= -55 -> "отличный"
    rssi >= -67 -> "хороший"
    rssi >= -75 -> "слабый"
    else -> "очень слабый"
}
