
package com.ridecalculator.app.model

/**
 * Raw values extracted by on-device OCR from a single screen frame.
 */
data class RideOfferData(
    val farePkr: Double?,
    val pickupDistanceKm: Double?,
    val destinationTripDistanceKm: Double?,
    val isAmbiguous: Boolean = false
) {
    companion object {
        val EMPTY = RideOfferData(
            farePkr = null,
            pickupDistanceKm = null,
            destinationTripDistanceKm = null,
            isAmbiguous = false
        )
    }
}

/**
 * Final evaluated result produced by RideDecisionEngine.
 */
data class RideEvaluationResult(
    val verdict: RideVerdict,
    val offer: RideOfferData,
    val ratePerKm: Double?,
    val reason: String
)
