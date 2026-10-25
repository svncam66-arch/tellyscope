// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Proc

/** The Apps tab's own tabs: what's running now, what the user installed, and what came with the TV. */
enum class AppsTab(val label: String) { Running("Активные"), Installed("Установленные"), BuiltIn("Системные") }

/** One Apps tab's list: the apps the user can change, then the [system] ones they can't, under their own header. */
data class AppsSplit(val apps: List<AppInfo>, val system: List<AppInfo>)

/** Locked, as [AppRow] shows it: protected, and not offered as the new home screen or the way back. */
private fun locked(app: AppInfo, all: List<AppInfo>) =
    app.protectedReason != null && (app.homeInstead == null || !app.enabled || homeLabel(all, app) == null) &&
        (app.homeBack == null || !app.enabled)

/**
 * Splits the TV's apps for [tab]. Running: apps using RAM now. Installed: the user's own apps,
 * all in one list, a locked home screen keeping its lock. Built-in: what came with the TV.
 * Running and Built-in put the apps the user can't change last, in [AppsSplit.system].
 */
internal fun splitApps(apps: List<AppInfo>, procs: List<Proc>, tab: AppsTab): AppsSplit {
    val running = procs.filter { it.ramMb > 0 }.mapNotNull { it.packageName }.toSet()
    val shown = when (tab) {
        AppsTab.Running -> apps.filter { it.packageName in running }
        AppsTab.Installed -> apps.filter { !it.system }
        AppsTab.BuiltIn -> apps.filter { it.system }
    }.sortedWith(compareBy({ locked(it, apps) }, { !it.enabled }, { it.system }, { it.label }))
    if (tab == AppsTab.Installed) return AppsSplit(shown, emptyList())
    val (system, rest) = shown.partition { locked(it, apps) }
    return AppsSplit(rest, system)
}

/** "System (N)" over the apps the TV needs; tapping it folds the list away or opens it again. */
@Composable
internal fun SystemHeader(count: Int, folded: Boolean, onFold: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onFold(!folded) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${Copy.SYSTEM} ($count)", style = MaterialTheme.typography.titleSmall)
            Text(Copy.SYSTEM_WHY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (folded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (folded) "Show system apps" else "Hide system apps",
        )
    }
}
