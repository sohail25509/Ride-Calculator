
package com.ridecalculator.app.engine

import com.ridecalculator.app.model.RideEvaluationResult
import com.ridecalculator.app.model.RideOfferData
import com.ridecalculator.app.model.RideVerdict
import java.util.Locale

class RideDecisionEngine {

    companion object {
        const val MIN_ACCEPTABLE_RATE_PKR_PER_KM = 30.0
        const val MAX_ACCEPTABLE_PICKUP_KM = 1.0
    }

    fun evaluate(offer: RideOfferData): RideEvaluationResult {

        if (offer.isAmbiguous) {
            return dataNotFound(offer, "Ambiguous OCR values detected")
        }

        val fare = offer.farePkr
        val pickupKm = offer.pickupDistanceKm
        val destinationKm = offer.destinationTripDistanceKm

        if (fare == null || pickupKm == null || destinationKm == null) {
            return dataNotFound(offer, "Required ride data is missing")
        }

        if (!fare.isFinite() || !pickupKm.isFinite() || !destinationKm.isFinite()) {
            return dataNotFound(offer, "Invalid numeric values")
        }

        if (fare <= 0.0 || pickupKm < 0.0 || destinationKm <= 0.0) {
            return dataNotFound(offer, "Invalid fare or distance")
        }

        // Calculate fare per km using destination distance ONLY.
        val ratePerKm = fare / destinationKm

        if (!ratePerKm.isFinite()) {
            return dataNotFound(offer, "Invalid rate calculation")
        }

        val isRateAcceptable = ratePerKm >= MIN_ACCEPTABLE_RATE_PKR_PER_KM
        val isPickupAcceptable = pickupKm <= MAX_ACCEPTABLE_PICKUP_KM

        val formattedRate = String.format(Locale.US, "%.1f", ratePerKm)
        val formattedPickup = String.format(Locale.US, "%.2f", pickupKm)

        return if (isRateAcceptable && isPickupAcceptable) {
            RideEvaluationResult(
                verdict = RideVerdict.UTHAA_LO,
                offer = offer,
                ratePerKm = ratePerKm,
                reason = "Rs. $formattedRate/km & pickup $formattedPickup km"
            )
        } else {
            RideEvaluationResult(
                verdict = RideVerdict.MAT_UTHAO,
                offer = offer,
                ratePerKm = ratePerKm,
                reason = "Rs. $formattedRate/km, pickup $formattedPickup km"
            )
        }
    }

    private fun dataNotFound(
        offer: RideOfferData,
        reason: String
    ): RideEvaluationResult {
        return RideEvaluationResult(
            verdict = RideVerdict.DATA_NOT_FOUND,
            offer = offer,
            ratePerKm = null,
            reason = reason
        )
    }
}
