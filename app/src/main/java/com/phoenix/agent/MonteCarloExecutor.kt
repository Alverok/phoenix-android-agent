package com.phoenix.agent

import java.util.concurrent.ThreadLocalRandom

/** Estimates pi by Monte-Carlo sampling of the unit square. */
class MonteCarloExecutor {

    data class Result(val output: String, val executionTimeMs: Long)

    /**
     * @throws IllegalArgumentException if [samples] is not positive
     */
    fun execute(samples: Long): Result {
        require(samples > 0) { "samples must be positive, was $samples" }

        val random = ThreadLocalRandom.current()
        val start = System.nanoTime()
        var inside = 0L
        var i = 0L

        while (i < samples) {
            val x = random.nextDouble()
            val y = random.nextDouble()
            if (x * x + y * y <= 1.0) {
                inside++
            }
            i++
        }

        val pi = 4.0 * inside / samples
        return Result("Computed Monte-Carlo pi ~= $pi", (System.nanoTime() - start) / 1_000_000)
    }
}
