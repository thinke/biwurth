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
