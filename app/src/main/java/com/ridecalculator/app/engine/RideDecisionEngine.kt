
package com.ridecalculator.app.engine

import com.ridecalculator.app.model.RideEvaluationResult
import com.ridecalculator.app.model.RideOfferData
import com.ridecalculator.app.model.RideVerdict
import java.util.Locale

class RideDecisionEngine {

    fun evaluate(offer: RideOfferData): RideEvaluationResult {
        val fare = offer.farePkr
        val pickup = offer.pickupDistanceKm
        val destination = offer.destinationTripDistanceKm

        if (
            offer.isAmbiguous ||
            fare == null ||
            pickup == null ||
            destination == null ||
            !fare.isFinite() ||
            !pickup.isFinite() ||
            !destination.isFinite() ||
            fare <= 0.0 ||
            pickup < 0.0 ||
            destination <= 0.0
        ) {
            return RideEvaluationResult(
                verdict = RideVerdict.DATA_NOT_FOUND,
                offer = offer,
                ratePerKm = null,
                reason = "Required ride information is missing or invalid."
            )
        }

        // Sirf destination distance use hogi. Pickup distance add nahi hogi.
        val ratePerKm = fare / destination

        val verdict = if (ratePerKm >= 30.0 && pickup <= 1.0) {
            RideVerdict.UTHAA_LO
        } else {
            RideVerdict.MAT_UTHAO
        }

        return RideEvaluationResult(
            verdict = verdict,
            offer = offer,
            ratePerKm = ratePerKm,
            reason = String.format(
                Locale.US,
                "Rs. %.2f per km | Pickup: %.2f km",
                ratePerKm,
                pickup
            )
        )
    }
}
