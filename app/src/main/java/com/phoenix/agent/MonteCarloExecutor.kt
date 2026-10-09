package com.phoenix.agent

class MonteCarloExecutor {
    data class Result(val output: String, val executionTimeMs: Long)
    fun execute(samples: Long): Result {
        val start = System.nanoTime()
        var inside = 0L
        repeat(samples.toInt()) {
            val x = Math.random(); val y = Math.random()
            if (x * x + y * y <= 1.0) inside++
        }
        val pi = 4.0 * inside.toDouble() / samples
        return Result("Computed Monte-Carlo pi ~= $pi", (System.nanoTime() - start) / 1_000_000)
    }
}