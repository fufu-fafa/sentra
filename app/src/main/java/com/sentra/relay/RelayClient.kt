package com.sentra.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A relay reply that wasn't 2xx. [status] 0 means the relay couldn't be reached. */
class RelayException(val status: Int, message: String) : IOException(message)

/** Minimal JSON-over-HTTP client for relay/server.mjs. No dependencies beyond the platform. */
class RelayClient(private val baseUrl: String, private val token: String?) {

    suspend fun get(path: String): JSONObject = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call("POST", path, body)
    suspend fun put(path: String, body: JSONObject): JSONObject = call("PUT", path, body)
    suspend fun delete(path: String): JSONObject = call("DELETE", path, null)

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val c = open(path)
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw RelayException(code, json.optString("error", "HTTP $code"))
            json
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(0, e.message ?: "relay unreachable")
        } finally {
            c.disconnect()
        }
    }

    /** An open connection with auth set; the caller sets the method and reads it. */
    fun open(path: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
}
