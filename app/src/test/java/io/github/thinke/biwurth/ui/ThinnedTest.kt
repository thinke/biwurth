package io.github.thinke.biwurth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinnedTest {
    private val start = 0L
    private val end = 1_000_000L

    @Test
    fun shortLinesStayAsTheyAre() {
        val points = (0 until 40).map { it * 1000L to it.toFloat() }
        assertSame(points, points.thinned(start, end, columns = 10))
    }

    @Test
    fun atMostFourPointsPerColumnInTimeOrder() {
        val points = (0 until 100_000).map { it * 10L to (it % 97).toFloat() }
        val thinned = points.thinned(start, end, columns = 500)
        assertTrue("${thinned.size} points", thinned.size <= 500 * 4)
        assertEquals(thinned.sortedBy { it.first }, thinned)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
    }

    @Test
    fun spikesSurvive() {
        // A flat line with one high and one low reading in the middle of a busy stretch.
        val points = (0 until 50_000).map { i ->
            val value = when (i) {
                20_000 -> 2_000f
                30_000 -> -50f
                else -> 100f
            }
            i * 20L to value
        }
        val thinned = points.thinned(start, end, columns = 200)
        assertEquals(2_000f, thinned.maxOf { it.second })
        assertEquals(-50f, thinned.minOf { it.second })
    }
}

class BucketAveragesTest {
    @Test
    fun aReadingHoldsUntilTheNext() {
        // 10 for the first quarter of the span, then 30: the first half averages 20.
        val points = listOf(0L to 10f, 250L to 30f)
        val halves = points.bucketAverages(0L, 1000L, 2)
        assertEquals(20f, halves[0], 0.001f)
        assertEquals(30f, halves[1], 0.001f)
    }

    @Test
    fun slicesBeforeTheFirstReadingAreEmpty() {
        val values = listOf(600L to 5f).bucketAverages(0L, 1000L, 4)
        assertTrue(values[0].isNaN())
        assertTrue(values[1].isNaN())
        assertEquals(5f, values[3], 0.001f)
    }
}
