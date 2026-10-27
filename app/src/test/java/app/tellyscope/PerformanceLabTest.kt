// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.PerformanceLab
import app.tellyscope.data.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceLabTest {
    private fun s(at: Long, cpu: Int, available: Boolean = true, storage: Double? = null): Snapshot =
        Snapshot(at, cpu, 512, 1024, storage, 12.0, null, 50, null, emptyList(), cpuAvailable = available)

    @Test fun reportsMeanAndSampleVariationFromDistinctTimestamps() {
        val run = PerformanceLab.summarize(listOf(s(2, 20), s(1, 10), s(3, 30), s(3, 30)))!!
        assertEquals(3, run.observations)
        assertEquals(1L, run.firstAt)
        assertEquals(3L, run.lastAt)
        assertEquals(20.0, run.cpuPercent!!.average, 0.0001)
        assertEquals(10.0, run.cpuPercent!!.standardDeviation, 0.0001)
        assertEquals(3, run.ramUsedMb!!.n)
        assertNull(run.temperatureC)
        assertNull(run.storageUsedGb)
    }

    @Test fun absentCpuNeverBecomesFakeZero() {
        val run = PerformanceLab.summarize(listOf(s(1, 0, false), s(2, 0, false), s(3, 0, false)))!!
        assertNull(run.cpuPercent)
        assertEquals(512.0, run.ramUsedMb!!.average, 0.0001)
    }

    @Test fun tooFewIndependentReadingsAreNotTrials() {
        assertNull(PerformanceLab.summarize(listOf(s(1, 20), s(1, 30), s(2, 50))))
    }

    @Test fun changeIsOnlyAnObservedDelta() {
        val before = PerformanceLab.summarize(listOf(s(1, 10),s(2, 20),s(3, 30)))!!
        val after = PerformanceLab.summarize(listOf(s(4, 30),s(5, 40),s(6, 50)))!!
        assertEquals(20.0, PerformanceLab.change(before.cpuPercent,after.cpuPercent)!!, 0.0001)
        assertNull(PerformanceLab.change(before.storageUsedGb,after.storageUsedGb))
    }
}
