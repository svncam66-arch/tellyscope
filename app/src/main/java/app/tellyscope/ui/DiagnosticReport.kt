// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.data.DeviceState
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plain-text local diagnostic report (spec #45-A).
 *
 * Sensitive network identifiers, SSIDs, process names, package identifiers,
 * ADB keys and application labels are deliberately NOT exported.
 * Only data already obtained through ADB is reported; missing readings
 * remain missing, never fabricated as zero.
 */
object DiagnosticReport {
    fun render(state: UiState, createdAt: Long = System.currentTimeMillis()): String {
        val device = state.selected ?: return "TV Optimizer Pro\nНет выбранного устройства.\n"
        val ui = state.device
        val current = device.state == DeviceState.Online
        val sample = ui.snapshot
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(createdAt))
        fun text(value: String?): String = value
            ?.replace(Regex("[\\r\\n\\t\\p{Cntrl}]"), " ")
            ?.trim()
            ?.take(100)
            ?.takeIf { it.isNotBlank() } ?: "нет данных"
        fun gb(value: Double?): String = value?.let { "%.2f ГБ".format(Locale.ROOT, it) } ?: "нет данных"
        fun time(value: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value))

        return buildString {
            appendLine("TV Optimizer Pro — локальный диагностический отчёт")
            appendLine("Создан: $now")
            appendLine("Состояние подключения: " + when (device.state) {
                DeviceState.Online -> "подключено"
                DeviceState.Offline -> "не в сети"
                DeviceState.Waiting -> "подключение"
                DeviceState.NeedsPairing -> "требуется сопряжение"
                DeviceState.NotAllowed -> "отладка не разрешена"
                DeviceState.Found -> "найдено, не подключено"
            })
            appendLine("Производитель: " + text(device.manufacturer))
            appendLine("Модель: " + text(device.model))
            appendLine("Версия Android: " + text(device.android))
            appendLine("ADB Android API: " + (ui.capabilities?.sdk?.toString() ?: "нет данных"))
            appendLine("Номер сборки: " + text(ui.capabilities?.buildId))
            appendLine("Проверенная модель/прошивка: не подтверждена этим отчётом")
            appendLine()
            if (sample == null) {
                appendLine("Измерения: нет данных")
            } else {
                appendLine("Измерения: " + if (current) "последние доступные" else "устаревшие (устройство не в сети)")
                appendLine("Время измерения: " + time(sample.at))
                appendLine("Загрузка CPU: ${sample.cpuPercent}%")
                appendLine("RAM: ${sample.ramUsedMb} / ${sample.ramTotalMb} МБ")
                appendLine("Накопитель занято: " + gb(sample.storageUsedGb))
                appendLine("Накопитель всего: " + gb(sample.storageTotalGb))
                appendLine("Температура: " + (sample.tempC?.let { "$it °C" } ?: "нет данных"))
                appendLine("Сеть, байт/с: " + (sample.netBytesPerSec?.toString() ?: "нет данных"))
                appendLine("Процессов в доступной статистике: ${sample.procs.size}")
            }
            appendLine()
            appendLine("Приложений в текущем списке: ${ui.apps.size}")
            appendLine("Системных приложений: ${ui.apps.count { it.system }}")
            appendLine("Отключённых приложений: ${ui.apps.count { !it.enabled }}")
            appendLine("Записей истории: ${ui.history.size}")
            appendLine()
            appendLine("Приватность: IP, MAC, SSID, ключи ADB, имена процессов и идентификаторы пакетов не включены.")
            appendLine("Примечание: этот отчёт не удостоверяет безопасность оптимизаций или совместимость прошивки.")
        }
    }
}
