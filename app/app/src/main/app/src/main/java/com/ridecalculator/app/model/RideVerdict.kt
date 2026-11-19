
package com.ridecalculator.app.model

/**
 * Represents the evaluation outcome for a visible inDrive ride request.
 */
enum class RideVerdict(val displayText: String) {
    UTHAA_LO("UTHAA LO"),
    MAT_UTHAO("MAT UTHAO"),
    DATA_NOT_FOUND("DATA NOT FOUND")
}
