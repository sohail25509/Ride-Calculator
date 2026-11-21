package com.ridecalculator.app.ocr

import com.ridecalculator.app.model.RideOfferData
import java.util.Locale

class RideOfferParser {

    companion object {
        private val ACCEPT_FARE_REGEX = Regex(
            """(?i)\\baccept\\s+for\\s+(?:rs\\.?|pkr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)"""
        )

        private val FARE_REGEX = Regex(
            """(?i)(?:\\b(?:rs\\.?|pkr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)|([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:rs\\.?|pkr)\\b)"""
        )

        // Comma decimal is common in the inDrive map bubble, e.g. 7,2 km.
        private val DISTANCE_REGEX = Regex(
            """(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(km|kms|kilometers?|kilometres?|m|meters?|metres?)\\b"""
        )

        private val PICKUP_LABEL = Regex(
            """(?i)\\b(pick[\\s-]?up|from|your location|distance to pickup)\\b"""
        )

        private val DESTINATION_LABEL = Regex(
            """(?i)\\b(destination|drop[\\s-]?off|to destination|trip distance|ride distance)\\b"""
        )
    }

    fun parse(text: String): RideOfferData {
        if (text.isBlank()) return RideOfferData.EMPTY

        val lines = text.lines()
            .map { it.trim().replace(Regex("""\\s+"""), " ") }
            .filter { it.isNotBlank() }

        val fareResult = extractFare(lines)
        var pickupResult = extractDistanceForLabel(lines, PICKUP_LABEL)
        var destinationResult = extractDistanceForLabel(lines, DESTINATION_LABEL)

        // inDrive map bubbles often show two unlabeled distances in order:
        // pickup in meters (blue bubble), then destination in kilometers (green bubble).
        // Use this fallback only when BOTH labels are absent and there are exactly
        // two unambiguous distance readings with one meter value and one km value.
        if (pickupResult.value == null &&
            destinationResult.value == null &&
            !pickupResult.ambiguous &&
            !destinationResult.ambiguous
        ) {
            val mapDistances = extractDistanceMeasurements(lines)
            if (mapDistances.size == 2) {
                val pickup = mapDistances.singleOrNull { it.unitIsMeters }
                val destination = mapDistances.singleOrNull { !it.unitIsMeters }
                if (pickup != null && destination != null) {
                    pickupResult = ExtractionResult(pickup.kilometers)
                    destinationResult = ExtractionResult(destination.kilometers)
                }
            }
        }

        val ambiguous = fareResult.ambiguous ||
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

    private data class DistanceMeasurement(
        val kilometers: Double,
        val unitIsMeters: Boolean
    )

    private fun extractFare(lines: List<String>): ExtractionResult {
        // Prefer the actual "Accept for PKR..." fare. Suggested counter-offer
        // buttons (e.g. PKR325 / PKR363) must not make the fare ambiguous.
        val acceptedFares = ACCEPT_FARE_REGEX.findAll(lines.joinToString(" "))
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filter { it > 0.0 && it.isFinite() }
            .distinct()

        if (acceptedFares.isNotEmpty()) {
            return if (acceptedFares.size == 1) {
                ExtractionResult(acceptedFares.first())
            } else {
                ExtractionResult(null, ambiguous = true)
            }
        }

        val matches = lines.flatMap { line ->
            FARE_REGEX.findAll(line).mapNotNull { match ->
                val raw = match.groupValues.drop(1)
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
            if (!labelRegex.containsMatchIn(line)) continue

            val sameLineDistances = extractDistances(line)
            if (sameLineDistances.isNotEmpty()) {
                candidates.addAll(sameLineDistances)
                continue
            }

            val previousLine = lines.getOrNull(index - 1)
            val nextLine = lines.getOrNull(index + 1)

            val previousIsPureLabel = previousLine != null &&
                (PICKUP_LABEL.containsMatchIn(previousLine) ||
                    DESTINATION_LABEL.containsMatchIn(previousLine)) &&
                extractDistances(previousLine).isEmpty()

            val nextIsPureLabel = nextLine != null &&
                (PICKUP_LABEL.containsMatchIn(nextLine) ||
                    DESTINATION_LABEL.containsMatchIn(nextLine)) &&
                extractDistances(nextLine).isEmpty()

            if (previousIsPureLabel && nextLine != null) {
                candidates.addAll(extractDistances(nextLine))
            } else if (nextIsPureLabel && previousLine != null) {
                candidates.addAll(extractDistances(previousLine))
            } else if (nextLine != null && !nextIsPureLabel) {
                candidates.addAll(extractDistances(nextLine))
            } else if (previousLine != null && !previousIsPureLabel) {
                candidates.addAll(extractDistances(previousLine))
            }
        }

        val valid = candidates.filter { it >= 0.0 && it.isFinite() }.distinct()
        return when (valid.size) {
            0 -> ExtractionResult(null)
            1 -> ExtractionResult(valid.first())
            else -> ExtractionResult(null, ambiguous = true)
        }
    }

    private fun extractDistanceMeasurements(lines: List<String>): List<DistanceMeasurement> {
        return lines.flatMap { line ->
            DISTANCE_REGEX.findAll(line).mapNotNull { match ->
                val number = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                    ?: return@mapNotNull null
                val unit = match.groupValues[2].lowercase(Locale.ROOT)
                val isMeters = unit == "m" ||
                    unit.startsWith("meter") ||
                    unit.startsWith("metre")
                val kilometers = if (isMeters) number / 1000.0 else number
                if (kilometers >= 0.0 && kilometers.isFinite()) {
                    DistanceMeasurement(kilometers, isMeters)
                } else {
                    null
                }
            }.toList()
        }
    }

    private fun extractDistances(line: String): List<Double> {
        return extractDistanceMeasurements(listOf(line)).map { it.kilometers }
    }
}
