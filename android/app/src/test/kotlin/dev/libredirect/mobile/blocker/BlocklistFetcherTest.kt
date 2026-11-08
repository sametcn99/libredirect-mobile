package dev.libredirect.mobile.blocker

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class BlocklistFetcherTest {
    @Test
    fun `fetch returns content when response is valid`() =
        runBlocking {
            val content =
                """
                # Some comments
                0.0.0.0 ads.example.com
                127.0.0.1 tracker.example.net
                """.trimIndent()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(content.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }.build()

            val fetcher = BlocklistFetcher(client)
            val result = fetcher.fetch("https://example.com/hosts")

            assertNotNull(result)
            assertEquals(content, result)
        }

    @Test
    fun `fetch returns null when server returns error code`() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Not Found".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }.build()

            val fetcher = BlocklistFetcher(client)
            val result = fetcher.fetch("https://example.com/hosts")

            assertNull(result)
        }

    @Test
    fun `fetch returns null when downloaded content exceeds MAX_BYTES`() =
        runBlocking {
            // Generate content slightly exceeding MAX_BYTES
            val oversized = ByteArray((ContentBlocker.MAX_BYTES + 10).toInt()) { 'a'.code.toByte() }
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(oversized.toResponseBody("text/plain".toMediaType()))
                            .build()
                    }.build()

            val fetcher = BlocklistFetcher(client)
            val result = fetcher.fetch("https://example.com/hosts")

            assertNull(result)
        }

    @Test
    fun `fetch returns null when response has no valid hosts`() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("# Only comments\n\n".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }.build()

            val fetcher = BlocklistFetcher(client)
            val result = fetcher.fetch("https://example.com/hosts")

            assertNull(result)
        }

    @Test
    fun `fetch returns null on network exception`() =
        runBlocking {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        throw IOException("Connection reset")
                    }.build()

            val fetcher = BlocklistFetcher(client)
            val result = fetcher.fetch("https://example.com/hosts")

            assertNull(result)
        }
}
