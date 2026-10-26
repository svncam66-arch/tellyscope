// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.Snapshot
import app.tellyscope.ui.DeviceUi
import app.tellyscope.ui.DiagnosticReport
import app.tellyscope.ui.UiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private fun state(online: Boolean, snapshot: Snapshot? = null): UiState {
        val hit = ScanHit("192.168.0.31", 5555, ScanKind.Classic, "secret-living-room", "Xiaomi")
        val tv = Device("secret-device-id", hit, if (online) DeviceState.Online else DeviceState.Offline,
            "Mi Box", "Xiaomi", "12", 1L)
        val apps = listOf(
            AppInfo("com.secret.private.app", "Private app", null, false, true, null, "1"),
            AppInfo("com.secret.system", "System app", null, true, false, null, "1")
        )
        return UiState(
            devices = listOf(tv), selectedId = tv.id,
            device = DeviceUi(snapshot = snapshot, apps = apps)
        )
    }

    @Test fun reportRedactsNetworkAndPackageIdentifiers() {
        val report = DiagnosticReport.render(state(true), 0L)
        assertTrue(report.contains("Xiaomi"))
        assertTrue(report.contains("Приложений в текущем списке: 2"))
        assertFalse(report.contains("192.168.0.31"))
        assertFalse(report.contains("secret-living-room"))
        assertFalse(report.contains("secret-device-id"))
        assertFalse(report.contains("com.secret"))
        assertFalse(report.contains("Private app"))
        assertTrue(report.contains("Измерения: нет данных"))
    }

    @Test fun missingMetricsDoNotBecomeFabricatedZeros() {
        val sample = Snapshot(1000L, 13, 750, 2048, null, null, null, 20L, null, emptyList())
        val report = DiagnosticReport.render(state(false, sample), 2000L)
        assertTrue(report.contains("устаревшие"))
        assertTrue(report.contains("Загрузка CPU: 13%"))
        assertTrue(report.contains("Температура: нет данных"))
        assertTrue(report.contains("Накопитель занято: нет данных"))
        assertFalse(report.contains("192.168."))
    }

    @Test fun unavailableCpuAndRamAreNotReportedAsZero() {
        val snapshot = Snapshot(100L, 0, 0, 0, null, null, null, 0L, null, emptyList(),
            cpuAvailable = false, ramAvailable = false, uptimeAvailable = false)
        val report = DiagnosticReport.render(state(true, snapshot), 200L)
        assertTrue(report.contains("Загрузка CPU: нет данных"))
        assertTrue(report.contains("RAM: нет данных"))
    }

    @Test fun noDeviceProducesHonestEmptyReport() {
        val report = DiagnosticReport.render(UiState(), 0L)
        assertTrue(report.contains("Нет выбранного устройства"))
    }
}
