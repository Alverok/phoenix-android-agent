package com.phoenix.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonteCarloExecutorTest {

    private val executor = MonteCarloExecutor()

    @Test
    fun estimatesPiWithinASensibleTolerance() {
        val result = executor.execute(200_000)
        val pi = result.output.substringAfter("~= ").toDouble()

        assertEquals(Math.PI, pi, 0.05)
        assertTrue(result.executionTimeMs >= 0)
    }

    @Test
    fun aSingleSampleStillProducesAResult() {
        assertTrue(executor.execute(1).output.startsWith("Computed Monte-Carlo pi"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroSamples() {
        executor.execute(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSamples() {
        executor.execute(-5)
    }
}
