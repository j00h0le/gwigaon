package com.coral.gwigaon.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null) {
    /** Headers carry the API key and the user's token: only the method and path go into logs. */
    override fun toString(): String = "HttpRequest($method ${url.substringBefore('?')})"
}

data class HttpResponse(val status: Int, val body: String)

/** Sends one request. Throws [IOException] when there is no answer; any HTTP status is an answer. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * [HttpTransport] on the platform's [HttpURLConnection] (ADR 0009: four REST calls don't need an HTTP stack
 * dependency). Android's implementation accepts PATCH; the JVM's doesn't, so unit tests use POST.
 */
class UrlConnectionTransport(
    private val connectTimeout: Duration = 5.seconds,
    private val readTimeout: Duration = 10.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(io) {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = connectTimeout.inWholeMilliseconds.toInt()
            conn.readTimeout = readTimeout.inWholeMilliseconds.toInt()
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            HttpResponse(status, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
