package com.ridecalculator.app.model

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

data class RideEvaluationResult(
    val verdict: RideVerdict,
    val offer: RideOfferData,
    val ratePerKm: Double?,
    val reason: String
)
