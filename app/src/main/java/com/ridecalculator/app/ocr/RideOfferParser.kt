
package com.ridecalculator.app.ocr

import com.ridecalculator.app.model.RideOfferData
import java.util.Locale

class RideOfferParser {

    companion object {
        private val FARE_REGEX = Regex(
            """(?i)(?:\b(?:rs\.?|pkr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)|([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:rs\.?|pkr)\b)"""
        )

        private val DISTANCE_REGEX = Regex(
            """(?i)([0-9]+(?:\.[0-9]+)?)\s*(km|kms|kilometers?|kilometres?|m|meters?|metres?)\b"""
        )

        private val PICKUP_LABEL = Regex(
            """(?i)\b(pick[\s-]?up|from|your location|distance to pickup)\b"""
        )

        private val DESTINATION_LABEL = Regex(
            """(?i)\b(destination|drop[\s-]?off|to destination|trip distance|ride distance)\b"""
        )
    }

    fun parse(text: String): RideOfferData {
        if (text.isBlank()) {
            return RideOfferData.EMPTY
        }

        val lines = text.lines()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }

        val fareResult = extractFare(lines)
        val pickupResult = extractDistanceForLabel(lines, PICKUP_LABEL)
        val destinationResult = extractDistanceForLabel(lines, DESTINATION_LABEL)

        val ambiguous =
            fareResult.ambiguous ||
            pickupResult.ambiguous ||
            destinationResult.ambiguous

        return RideOfferData(
            farePkr = fareResult.value,
            pickupDistanceKm = pickupResult.value,
            destinationTripDistanceKm = destinationResult.value,
            isAmbiguous = ambiguous
        )
    }

    private data class ExtractionResult(
        val value: Double?,
        val ambiguous: Boolean = false
    )

    private fun extractFare(lines: List<String>): ExtractionResult {
        val matches = lines.flatMap { line ->
            FARE_REGEX.findAll(line).mapNotNull { match ->
                val raw = match.groupValues
                    .drop(1)
                    .firstOrNull { it.isNotBlank() }
                    ?: return@mapNotNull null

                raw.replace(",", "").toDoubleOrNull()
            }.toList()
        }.filter { it > 0.0 && it.isFinite() }

        val distinct = matches.distinct()

        return when (distinct.size) {
            0 -> ExtractionResult(null)
            1 -> ExtractionResult(distinct.first())
            else -> ExtractionResult(null, ambiguous = true)
        }
    }

    private fun extractDistanceForLabel(
        lines: List<String>,
        labelRegex: Regex
    ): ExtractionResult {
        val candidates = mutableListOf<Double>()

        for (index in lines.indices) {
            val line = lines[index]

            if (!labelRegex.containsMatchIn(line)) {
                continue
            }

            val sameLineDistances = extractDistances(line)

            if (sameLineDistances.isNotEmpty()) {
                candidates.addAll(sameLineDistances)
                continue
            }

            val previousLine = lines.getOrNull(index - 1)
            val nextLine = lines.getOrNull(index + 1)

            val previousIsPureLabel =
                previousLine != null &&
                (PICKUP_LABEL.containsMatchIn(previousLine) ||
                    DESTINATION_LABEL.containsMatchIn(previousLine)) &&
                extractDistances(previousLine).isEmpty()

            val nextIsPureLabel =
                nextLine != null &&
                (PICKUP_LABEL.containsMatchIn(nextLine) ||
                    DESTINATION_LABEL.containsMatchIn(nextLine)) &&
                extractDistances(nextLine).isEmpty()

            if (previousIsPureLabel && nextLine != null) {
                candidates.addAll(extractDistances(nextLine))
            } else if (nextIsPureLabel && previousLine != null) {
                candidates.addAll(extractDistances(previousLine))
            } else if (nextLine != null && previousLine == null) {
                candidates.addAll(extractDistances(nextLine))
            } else if (nextLine != null && !nextIsPureLabel) {
                candidates.addAll(extractDistances(nextLine))
            } else if (previousLine != null && !previousIsPureLabel) {
                candidates.addAll(extractDistances(previousLine))
            }
        }

        val valid = candidates
            .filter { it >= 0.0 && it.isFinite() }
            .distinct()

        return when (valid.size) {
            0 -> ExtractionResult(null)
            1 -> ExtractionResult(valid.first())
            else -> ExtractionResult(null, ambiguous = true)
        }
    }

    private fun extractDistances(line: String): List<Double> {
        return DISTANCE_REGEX.findAll(line).mapNotNull { match ->
            val number = match.groupValues[1].toDoubleOrNull()
                ?: return@mapNotNull null

            val unit = match.groupValues[2].lowercase(Locale.ROOT)

            val kilometers = when {
                unit == "m" ||
                    unit.startsWith("meter") ||
                    unit.startsWith("metre") -> number / 1000.0

                else -> number
            }

            kilometers.takeIf { it >= 0.0 && it.isFinite() }
        }.toList()
    }
}
