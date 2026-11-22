package com.ridecalculator.app.ocr

import com.ridecalculator.app.model.RideOfferData
import java.util.Locale

class RideOfferParser {

    companion object {
        private val ACCEPT_FARE_REGEX = Regex(
            """(?i)\baccept\s+for\s+(?:rs\.?|pkr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)"""
        )
        private val FARE_REGEX = Regex(
            """(?i)(?:\b(?:rs\.?|pkr)\s*([0-9][0-9,]*(?:\.[0-9]+)?)|([0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:rs\.?|pkr)\b)"""
        )
        private val DISTANCE_REGEX = Regex(
            """(?i)([0-9]+(?:[.,][0-9]+)?)\s*(km|kms|kilometers?|kilometres?|m|meters?|metres?)\b"""
        )
        private val PICKUP_LABEL = Regex(
            """(?i)\b(pick[\s-]?up|from|your location|distance to pickup)\b"""
        )
        private val DESTINATION_LABEL = Regex(
            """(?i)\b(destination|drop[\s-]?off|to destination|trip distance|ride distance)\b"""
        )
    }

    fun parse(text: String): RideOfferData {
        if (text.isBlank()) return RideOfferData.EMPTY
        val lines = text.lines()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }

        val fare = extractFare(lines)
        var pickup = extractDistanceForLabel(lines, PICKUP_LABEL)
        var destination = extractDistanceForLabel(lines, DESTINATION_LABEL)

        if (pickup.value == null && destination.value == null &&
            !pickup.ambiguous && !destination.ambiguous
        ) {
            val measurements = extractDistanceMeasurements(lines)
            if (measurements.size == 2) {
                val pickupMeasurement = measurements.singleOrNull { it.unitIsMeters }
                val destinationMeasurement = measurements.singleOrNull { !it.unitIsMeters }
                if (pickupMeasurement != null && destinationMeasurement != null) {
                    pickup = ExtractionResult(pickupMeasurement.kilometers)
                    destination = ExtractionResult(destinationMeasurement.kilometers)
                }
            }

            // On the inDrive offer card, the pickup distance can appear twice:
            // once in the map bubble and once above the fare. The destination
            // distance appears once in its own map bubble. Only use this pattern
            // when an accepted fare is clearly present and exactly two distinct
            // kilometer values exist; otherwise leave the distances unknown.
            if (pickup.value == null && destination.value == null &&
                !pickup.ambiguous && !destination.ambiguous &&
                fare.value != null && !fare.ambiguous
            ) {
                val kilometerValues = measurements
                    .filter { !it.unitIsMeters }
                    .groupingBy { it.kilometers }
                    .eachCount()
                val repeated = kilometerValues.filterValues { it >= 2 }.keys
                val single = kilometerValues.filterValues { it == 1 }.keys
                if (kilometerValues.size == 2 && repeated.size == 1 && single.size == 1) {
                    pickup = ExtractionResult(repeated.first())
                    destination = ExtractionResult(single.first())
                }
            }
        }

        return RideOfferData(
            farePkr = fare.value,
            pickupDistanceKm = pickup.value,
            destinationTripDistanceKm = destination.value,
            isAmbiguous = fare.ambiguous || pickup.ambiguous || destination.ambiguous
        )
    }

    private data class ExtractionResult(val value: Double?, val ambiguous: Boolean = false)
    private data class DistanceMeasurement(val kilometers: Double, val unitIsMeters: Boolean)

    private fun extractFare(lines: List<String>): ExtractionResult {
        val acceptedFares = ACCEPT_FARE_REGEX.findAll(lines.joinToString(" "))
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .filter { it > 0.0 && it.isFinite() }
            .distinct()
            .toList()

        if (acceptedFares.isNotEmpty()) {
            return if (acceptedFares.size == 1) ExtractionResult(acceptedFares.first())
            else ExtractionResult(null, true)
        }

        val fares = lines.flatMap { line ->
            FARE_REGEX.findAll(line).mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                    ?.replace(",", "")?.toDoubleOrNull()
            }.toList()
        }.filter { it > 0.0 && it.isFinite() }.distinct()

        return when (fares.size) {
            0 -> ExtractionResult(null)
            1 -> ExtractionResult(fares.first())
            else -> ExtractionResult(null, true)
        }
    }

    private fun extractDistanceForLabel(lines: List<String>, label: Regex): ExtractionResult {
        val candidates = mutableListOf<Double>()
        for (index in lines.indices) {
            if (!label.containsMatchIn(lines[index])) continue
            val sameLine = extractDistances(lines[index])
            if (sameLine.isNotEmpty()) {
                candidates.addAll(sameLine)
                continue
            }
            val previous = lines.getOrNull(index - 1)
            val next = lines.getOrNull(index + 1)
            if (previous != null && next != null &&
                (PICKUP_LABEL.containsMatchIn(previous) || DESTINATION_LABEL.containsMatchIn(previous)) &&
                extractDistances(previous).isEmpty()
            ) {
                candidates.addAll(extractDistances(next))
            } else if (next != null && !PICKUP_LABEL.containsMatchIn(next) &&
                !DESTINATION_LABEL.containsMatchIn(next)
            ) {
                candidates.addAll(extractDistances(next))
            } else if (previous != null) {
                candidates.addAll(extractDistances(previous))
            }
        }
        val values = candidates.filter { it >= 0.0 && it.isFinite() }.distinct()
        return when (values.size) {
            0 -> ExtractionResult(null)
            1 -> ExtractionResult(values.first())
            else -> ExtractionResult(null, true)
        }
    }

    private fun extractDistanceMeasurements(lines: List<String>): List<DistanceMeasurement> =
        lines.flatMap { line ->
            DISTANCE_REGEX.findAll(line).mapNotNull { match ->
                val number = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                    ?: return@mapNotNull null
                val unit = match.groupValues[2].lowercase(Locale.ROOT)
                val meters = unit == "m" || unit.startsWith("meter") || unit.startsWith("metre")
                val km = if (meters) number / 1000.0 else number
                if (km >= 0.0 && km.isFinite()) DistanceMeasurement(km, meters) else null
            }.toList()
        }

    private fun extractDistances(line: String): List<Double> =
        extractDistanceMeasurements(listOf(line)).map { it.kilometers }
}
