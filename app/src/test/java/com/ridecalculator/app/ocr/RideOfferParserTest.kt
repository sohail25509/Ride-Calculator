package com.ridecalculator.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
