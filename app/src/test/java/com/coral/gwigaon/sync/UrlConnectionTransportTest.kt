package com.coral.gwigaon.sync

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/** Against a real local HTTP server (the JDK's), since this class is only the wire. */
class UrlConnectionTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var seen: Triple<String, String?, String>? = null
    private val transport = UrlConnectionTransport(2.seconds, 2.seconds, Dispatchers.IO)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen = Triple(ex.requestMethod, ex.requestHeaders.getFirst("apikey"), body)
            val (status, out) = if (ex.requestURI.path == "/fail") 400 to """{"code":"23514"}""" else 201 to """{"ok":true}"""
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun sendsMethodHeadersAndBodyAndReadsTheAnswer() = runBlocking {
        val r = transport.send(HttpRequest("POST", "$base/ok", mapOf("apikey" to "k1"), """{"a":"한글"}"""))

        assertEquals(HttpResponse(201, """{"ok":true}"""), r)
        assertEquals(Triple("POST", "k1", """{"a":"한글"}"""), seen)
    }

    @Test
    fun errorStatusIsAnAnswerWithItsBody() = runBlocking {
        assertEquals(HttpResponse(400, """{"code":"23514"}"""), transport.send(HttpRequest("POST", "$base/fail", emptyMap(), "{}")))
    }

    @Test
    fun noServerIsAnIOException() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val e = runCatching { transport.send(HttpRequest("GET", "http://127.0.0.1:$port/", emptyMap())) }.exceptionOrNull()
        assertTrue(e is IOException)
    }
}
