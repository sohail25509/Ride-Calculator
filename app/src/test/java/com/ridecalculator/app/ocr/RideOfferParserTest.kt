package com.ridecalculator.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.ridecalculator.app.engine.RideDecisionEngine
import com.ridecalculator.app.model.RideOfferData
import com.ridecalculator.app.model.RideVerdict
import org.junit.Test

class RideOfferParserTest {

    private val parser = RideOfferParser()

    @Test
    fun parsesInDriveMapBubblesAndAcceptedFare() {
        val text = """
            Ride request
            3 min
            622 m
            24 min
            7,2 km
            PKR295 Fair price
            Accept for PKR295
            PKR325
            PKR363
        """.trimIndent()

        val result = parser.parse(text)

        assertEquals(295.0, result.farePkr!!, 0.001)
        assertEquals(0.622, result.pickupDistanceKm!!, 0.001)
        assertEquals(7.2, result.destinationTripDistanceKm!!, 0.001)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun fillsMissingDestinationWhenPickupLabelWasRecognizedButDestinationLabelWasNot() {
        val result = parser.parse(
            """
                Pickup
                622 m
                24 min
                7,2 km
                Accept for PKR295
            """.trimIndent()
        )
        assertEquals(295.0, result.farePkr!!, 0.001)
        assertEquals(0.622, result.pickupDistanceKm!!, 0.001)
        assertEquals(7.2, result.destinationTripDistanceKm!!, 0.001)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun doesNotGuessDestinationWhenOnlyOneUnlabelledDistanceExists() {
        val result = parser.parse(
            """
                PKR147
                ~2.3 km
                Accept for PKR147
            """.trimIndent()
        )

        assertEquals(147.0, result.farePkr!!, 0.001)
        assertNull(result.pickupDistanceKm)
        assertNull(result.destinationTripDistanceKm)
    }
    
    @Test
    fun parsesDecimalCommaAndCompactFareFormats() {
        val result = parser.parse(
            """
                622m
                7,2km
                Accept for PKR295
                PKR325
                PKR363
            """.trimIndent()
        )
        assertEquals(295.0, result.farePkr!!, 0.001)
        assertEquals(0.622, result.pickupDistanceKm!!, 0.001)
        assertEquals(7.2, result.destinationTripDistanceKm!!, 0.001)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun refusesToChooseBetweenMultipleFareOptionsWithoutAcceptedFare() {
        val result = parser.parse(
            """
                PKR295
                PKR325
                PKR363
                622 m
                7.2 km
            """.trimIndent()
        )
        assertNull(result.farePkr)
        assertEquals(true, result.isAmbiguous)
    }

    @Test
    fun decisionEngineUsesDestinationDistanceOnlyAndAcceptsExactThresholds() {
        val result = RideDecisionEngine().evaluate(
            RideOfferData(300.0, 1.0, 10.0)
        )
        assertEquals(RideVerdict.UTHAA_LO, result.verdict)
        assertEquals(30.0, result.ratePerKm!!, 0.001)
    }

    @Test
    fun decisionEngineRejectsPickupOverOneKmEvenWhenRateIsGood() {
        val result = RideDecisionEngine().evaluate(
            RideOfferData(200.0, 1.01, 5.0)
        )
        assertEquals(RideVerdict.MAT_UTHAO, result.verdict)
        assertEquals(40.0, result.ratePerKm!!, 0.001)
    }

    @Test
    fun decisionEngineReturnsDataNotFoundForMissingOrZeroTripDistance() {
        val engine = RideDecisionEngine()
        val missing = engine.evaluate(RideOfferData(295.0, 0.6, null))
        val zero = engine.evaluate(RideOfferData(295.0, 0.6, 0.0))
        assertEquals(RideVerdict.DATA_NOT_FOUND, missing.verdict)
        assertEquals(RideVerdict.DATA_NOT_FOUND, zero.verdict)
    }

    @Test
    fun parserDoesNotCarryValuesBetweenDifferentRideScreens() {
        val first = parser.parse("Accept for PKR295\n622 m\n7.2 km")
        val second = parser.parse("Accept for PKR180\n1400 m\n5 km")
        assertEquals(295.0, first.farePkr!!, 0.001)
        assertEquals(180.0, second.farePkr!!, 0.001)
        assertEquals(7.2, first.destinationTripDistanceKm!!, 0.001)
        assertEquals(5.0, second.destinationTripDistanceKm!!, 0.001)
    }

    @Test
    fun parsesInDriveOfferWhenPickupDistanceIsRepeatedOnCardAndMap() {
        val result = parser.parse(
            """
                13 min
                4.3 km
                6 min
                1.8 km
                ~4.3 km
                PKR80
                Accept for PKR80
                PKR88
                PKR95
            """.trimIndent()
        )
        assertEquals(80.0, result.farePkr!!, 0.001)
        assertEquals(4.3, result.pickupDistanceKm!!, 0.001)
        assertEquals(1.8, result.destinationTripDistanceKm!!, 0.001)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun doesNotGuessWhenTwoUnlabelledKilometerDistancesHaveNoRepeatedPickup() {
        val result = parser.parse(
            """
                Accept for PKR80
                4.3 km
                1.8 km
            """.trimIndent()
        )
        assertEquals(80.0, result.farePkr!!, 0.001)
        assertNull(result.pickupDistanceKm)
        assertNull(result.destinationTripDistanceKm)
    }

}
