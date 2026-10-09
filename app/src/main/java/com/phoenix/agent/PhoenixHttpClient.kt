package com.phoenix.agent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PhoenixHttpClient {
    fun postJson(endpoint: String, body: String): String = request(endpoint, "POST", body)
    fun get(endpoint: String): Pair<Int, String> {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        return try {
            val code = conn.responseCode
            val text = if (code == 200) conn.inputStream.bufferedReader().use(BufferedReader::readText) else ""
            code to text
        } finally { conn.disconnect() }
    }
    private fun request(endpoint: String, method: String, body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code: $response")
            return response
        } finally { conn.disconnect() }
    }
}