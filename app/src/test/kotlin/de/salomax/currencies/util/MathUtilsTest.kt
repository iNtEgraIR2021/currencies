package de.salomax.currencies.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class MathUtilsTest {

    @Test
    fun calculateDifferenceTest() {
        assertEquals(10f, calculateDifference(100f, 110f))
        assertEquals(-10f, calculateDifference(100f, 90f))
        assertEquals(null, calculateDifference(null, 1f))
        assertEquals(null, calculateDifference(1f, null))
        assertEquals(null, calculateDifference(null, null))
        assertEquals(null, calculateDifference(Float.POSITIVE_INFINITY, 1f))
        assertEquals(null, calculateDifference(Float.NEGATIVE_INFINITY, 1f))
    }

    @Test
    fun getSignificantDecimalPlacesTest() {
        assertEquals(2, 1f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.9f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.991f.getSignificantDecimalPlaces(2))
        assertEquals(4, 0.0026f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.998f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.9991f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.99991f.getSignificantDecimalPlaces(2))
        assertEquals(2, 0.999991f.getSignificantDecimalPlaces(2))
        assertEquals(5, 0.9999991f.getSignificantDecimalPlaces(5))
    }

}
