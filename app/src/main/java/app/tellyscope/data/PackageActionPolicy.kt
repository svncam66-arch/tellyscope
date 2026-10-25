// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

/**
 * Conservative app-side preflight. ADB/backend checks remain mandatory:
 * a UI policy does not make a shell command safe by itself.
 *
 * Unknown noncritical system packages may be manually disabled, but cannot
 * be uninstalled for Android user 0 without separately verified trust evidence.
 */
object PackageActionPolicy {
    /** null means the requested action is allowed by this initial local gate. */
    fun refusal(app: AppInfo?, action: Action): String? {
        if (action !in setOf(Action.Stop, Action.Disable, Action.Uninstall)) return null
        if (app == null) return "Пакет не найден в актуальном списке устройства. Сначала обновите список."
        if (app.protectedReason != null) {
            return "Операция заблокирована: системный компонент защищён (${app.protectedReason})."
        }
        if (action == Action.Uninstall && (app.system || !app.removable)) {
            return "Удаление системного или неподтверждённого пакета запрещено. Можно рассмотреть ручное отключение с проверкой восстановления."
        }
        return null
    }
}
