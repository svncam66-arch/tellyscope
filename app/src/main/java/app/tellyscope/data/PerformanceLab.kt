// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import kotlin.math.sqrt

/** Descriptive stats from real ADB readings, never a synthetic device speed score. */
data class MeasuredMetric(val n: Int, val average: Double, val standardDeviation: Double)
data class PerformanceRun(
    val observations: Int,
    val firstAt: Long,
    val lastAt: Long,
    val cpuPercent: MeasuredMetric?,
    val ramUsedMb: MeasuredMetric?,
    val storageUsedGb: MeasuredMetric?,
    val temperatureC: MeasuredMetric?,
)

/**
 * Independent observed data points, not independently controlled performance trials.
 * Sample times, missing metrics and sample standard deviation must remain visible.
 */
object PerformanceLab {
    fun summarize(samples: List<Snapshot>): PerformanceRun? {
        val unique = samples.distinctBy { it.at }.sortedBy { it.at }
        if (unique.size < 3) return null
        fun metric(values: List<Double?>): MeasuredMetric? {
            val valid = values.filterNotNull().filter { it.isFinite() }
            if (valid.size < 3) return null
            val mean = valid.average()
            val variance = valid.sumOf { (it - mean) * (it - mean) } / (valid.size - 1)
            return MeasuredMetric(valid.size, mean, sqrt(variance))
        }
        return PerformanceRun(
            observations = unique.size,
            firstAt = unique.first().at,
            lastAt = unique.last().at,
            cpuPercent = metric(unique.map { if (it.cpuAvailable) it.cpuPercent.toDouble() else null }),
            ramUsedMb = metric(unique.map { if (it.ramAvailable && it.ramTotalMb > 0) it.ramUsedMb.toDouble() else null }),
            storageUsedGb = metric(unique.map { it.storageUsedGb }),
            temperatureC = metric(unique.map { it.tempC?.toDouble() }),
        )
    }

    /** Change in an observed quantity, after minus before. Does not imply better speed. */
    fun change(before: MeasuredMetric?, after: MeasuredMetric?): Double? =
        if (before != null && after != null) after.average - before.average else null
}
