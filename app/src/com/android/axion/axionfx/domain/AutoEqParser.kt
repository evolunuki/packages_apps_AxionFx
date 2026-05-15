/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.axion.axionfx.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val MIN_GAIN_CB = -1200
private const val MAX_GAIN_CB = 1200
private const val MIN_FILTER_GAIN_DB = -60f
private const val MAX_FILTER_GAIN_DB = 60f
private const val MIN_Q = 0.01f
private const val MAX_Q = 100f

private val NUMBER = "([+-]?\\d+(?:\\.\\d+)?)"
private val GRAPHIC_EQ_REGEX = Regex("(?is)GraphicEQ\\s*:\\s*([^\\r\\n]+)")
private val FREQUENCY_GAIN_REGEX = Regex("$NUMBER\\s+$NUMBER")
private val PREAMP_REGEX = Regex("(?i)\\bPreamp\\s*:\\s*$NUMBER\\s*dB")
private val PARAMETRIC_FILTER_REGEX = Regex(
    "(?i)Filter\\s+\\d+\\s*:\\s*ON\\s+(PK|PEAKING)\\s+Fc\\s+$NUMBER\\s*Hz\\s+Gain\\s+$NUMBER\\s*dB\\s+Q\\s+$NUMBER"
)

data class AutoEqImportResult(
    val bandGainsCentibels: IntArray,
    val preampCentibels: Int?,
)

object AutoEqParser {

    fun parse(text: String, targetFrequencies: FloatArray): AutoEqImportResult? {
        if (targetFrequencies.isEmpty()) return null
        val preampCentibels = PREAMP_REGEX.find(text)?.groupValues?.get(1)?.toFloatOrNull()?.toCentibels()
        parseGraphicEq(text, targetFrequencies)?.let { gains ->
            return AutoEqImportResult(gains, preampCentibels)
        }
        parseParametricEq(text, targetFrequencies)?.let { gains ->
            return AutoEqImportResult(gains, preampCentibels)
        }
        return null
    }

    private fun parseGraphicEq(text: String, targetFrequencies: FloatArray): IntArray? {
        val body = GRAPHIC_EQ_REGEX.find(text)?.groupValues?.get(1) ?: return null
        val points = FREQUENCY_GAIN_REGEX.findAll(body)
            .mapNotNull { match ->
                val frequency = match.groupValues[1].toFloatOrNull()
                val gain = match.groupValues[2].toFloatOrNull()
                if (frequency != null && gain != null && frequency > 0f) frequency to gain else null
            }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .toList()
        if (points.size < 2) return null
        return IntArray(targetFrequencies.size) { index ->
            interpolateGraphicGain(points, targetFrequencies[index]).toCentibels()
        }
    }

    private fun parseParametricEq(text: String, targetFrequencies: FloatArray): IntArray? {
        val filters = PARAMETRIC_FILTER_REGEX.findAll(text)
            .mapNotNull { match ->
                val frequency = match.groupValues[2].toFloatOrNull()
                val gain = match.groupValues[3].toFloatOrNull()
                val q = match.groupValues[4].toFloatOrNull()
                if (frequency != null && gain != null && q != null && frequency > 0f) {
                    PeakingFilter(
                        frequency = frequency,
                        gainDb = gain.coerceIn(MIN_FILTER_GAIN_DB, MAX_FILTER_GAIN_DB),
                        q = q.coerceIn(MIN_Q, MAX_Q),
                    )
                } else {
                    null
                }
            }
            .toList()
        if (filters.isEmpty()) return null
        return IntArray(targetFrequencies.size) { index ->
            filters.sumOf { it.gainAt(targetFrequencies[index]).toDouble() }.toFloat().toCentibels()
        }
    }
}

private data class PeakingFilter(
    val frequency: Float,
    val gainDb: Float,
    val q: Float,
) {
    fun gainAt(targetFrequency: Float): Float {
        val sampleRate = (targetFrequency.coerceAtLeast(frequency) * 8f).coerceAtLeast(48000f)
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * frequency / sampleRate
        val alpha = sin(w0) / (2f * q)
        val b0 = 1f + alpha * a
        val b1 = -2f * cos(w0)
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cos(w0)
        val a2 = 1f - alpha / a
        return biquadMagnitudeDb(
            b0 / a0,
            b1 / a0,
            b2 / a0,
            a1 / a0,
            a2 / a0,
            targetFrequency,
            sampleRate,
        )
    }
}

private fun interpolateGraphicGain(points: List<Pair<Float, Float>>, targetFrequency: Float): Float {
    if (targetFrequency <= points.first().first) return points.first().second
    if (targetFrequency >= points.last().first) return points.last().second
    val upperIndex = points.indexOfFirst { it.first >= targetFrequency }
    val lower = points[upperIndex - 1]
    val upper = points[upperIndex]
    val position = (ln(targetFrequency) - ln(lower.first)) / (ln(upper.first) - ln(lower.first))
    return lower.second + (upper.second - lower.second) * position
}

private fun Float.toCentibels(): Int =
    (this * 100f).roundToInt().coerceIn(MIN_GAIN_CB, MAX_GAIN_CB)

private fun biquadMagnitudeDb(
    b0: Float,
    b1: Float,
    b2: Float,
    a1: Float,
    a2: Float,
    frequency: Float,
    sampleRate: Float,
): Float {
    val omega = 2f * PI.toFloat() * frequency / sampleRate
    val cos1 = cos(omega)
    val sin1 = sin(omega)
    val cos2 = cos(2f * omega)
    val sin2 = sin(2f * omega)
    val nr = b0 + b1 * cos1 + b2 * cos2
    val ni = -b1 * sin1 - b2 * sin2
    val dr = 1f + a1 * cos1 + a2 * cos2
    val di = -a1 * sin1 - a2 * sin2
    val magnitude = sqrt((nr * nr + ni * ni) / (dr * dr + di * di)).coerceAtLeast(0.000001f)
    return 20f * log10(magnitude)
}
