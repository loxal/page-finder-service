// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>. All rights reserved.

package net.loxal.finder.jmh

import net.loxal.finder.controller.SiteController
import net.loxal.finder.dto.Result
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.openjdk.jmh.annotations.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@State(Scope.Benchmark)
@Fork(10)
@BenchmarkMode(Mode.Throughput)
@Threads(20)
open class BenchmarkOkHttpClientTest {
    @Benchmark
    fun hutilsStaticFile() {
        val request = Request.Builder().url("https://www.loxal.net").build()
        CALLER.newCall(request).execute().use { response ->
            assertEquals(HttpStatus.OK.value().toLong(), response.code.toLong())
            assertNotNull(response.body)
        }
    }

    @Benchmark
    fun echo() {
        val request = Request.Builder()
            .post("".toRequestBody())
            .url("https://hutils.loxal.net/echo").build()
        CALLER.newCall(request).execute().use { response ->
            assertEquals(HttpStatus.OK.value().toLong(), response.code.toLong())
            assertTrue(response.body.string().length > 200)
        }
    }

    @Benchmark
    fun whois() {
        val request = Request.Builder().url("https://hutils.loxal.net/whois").build()
        CALLER.newCall(request).execute().use { response ->
            assertTrue(response.body.string().length > 200)
        }
    }

    @Benchmark
    fun sisStaticFile() {
        // val request = Request.Builder().url("${SiteController.SIS_API_SERVICE_URL}/swagger-ui/index.html").build()
        val request = Request.Builder().url("https://voyk.loxal.net/swagger-ui/index.html").build()
        CALLER.newCall(request).execute().use { response ->
            assertEquals(HttpStatus.OK.value().toLong(), response.code.toLong())
            assertNotNull(response.body)
        }
    }

    @Benchmark
    fun search() {
        val randomSiteIndex = PSEUDO_ENTROPY.nextInt(SEARCH_DATA.size)
        val randomSiteId = SEARCH_DATA.keys.toTypedArray()[randomSiteIndex]
        val randomSite = SEARCH_DATA[randomSiteId]
        val randomQueryIndex = PSEUDO_ENTROPY.nextInt(SEARCH_QUERIES.size)
        val randomQuery = randomSite!!.keys.toTypedArray()[randomQueryIndex]
        val queryHits = randomSite[randomQuery]

        val request = Request.Builder()
                .url("${SiteController.SIS_API_SERVICE_URL}${SiteController.ENDPOINT}/$randomSiteId/search?query=$randomQuery")
                .build()
        CALLER.newCall(request).execute().use { response ->
            assertEquals(HttpStatus.OK.value().toLong(), response.code.toLong())
            if (queryHits == 0) {
                assertNotNull(response.body)
            } else {
                val result = MAPPER.readValue<Result>(response.body.charStream(), Result::class.java)
                assertTrue(
                    queryHits!! < result.results.size,
                    "queryHits: $queryHits - result.results.size: ${result.results.size}"
                )
                assertEquals(randomQuery, result.query)
            }
        }
    }

    @Benchmark
    fun autocomplete() {
        val randomSiteIndex = PSEUDO_ENTROPY.nextInt(SEARCH_DATA.size)
        val randomSiteId = AUTOCOMPLETE_DATA.keys.toTypedArray()[randomSiteIndex]
        val randomSite = AUTOCOMPLETE_DATA[randomSiteId]
        val randomQueryIndex = PSEUDO_ENTROPY.nextInt(AUTOCOMPLETE_QUERIES.size)
        val randomQuery = randomSite!!.keys.toTypedArray()[randomQueryIndex]
        val queryHits = randomSite[randomQuery]

        val request = Request.Builder()
                .url("${SiteController.SIS_API_SERVICE_URL}${SiteController.ENDPOINT}/$randomSiteId/autocomplete?query=$randomQuery")
                .build()
        CALLER.newCall(request).execute().use { response ->
            assertEquals(HttpStatus.OK.value().toLong(), response.code.toLong())
            val result = MAPPER.readValue<net.loxal.finder.dto.Autocomplete>(
                response.body.charStream(),
                net.loxal.finder.dto.Autocomplete::class.java
            )
            assertTrue(queryHits!! <= result.results.size, "$queryHits - ${result.results.size}")
        }
    }

    companion object {
        private val CALLER = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        internal val LOAD_SITE_ID: UUID = UUID.fromString("563714f1-96c0-4500-b366-4fc7e734fa1d")
        private val MAPPER = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
        private val PSEUDO_ENTROPY = Random()
        private val SEARCH_QUERIES = mutableMapOf<String, Int>()
        private val AUTOCOMPLETE_QUERIES = mutableMapOf<String, Int>()
        private val SEARCH_DATA = mutableMapOf<UUID, Map<String, Int>>()
        private val AUTOCOMPLETE_DATA = mutableMapOf<UUID, Map<String, Int>>()
        private val LOG = LoggerFactory.getLogger(BenchmarkOkHttpClientTest::class.java)

        init {
            SEARCH_QUERIES["hypothek"] = 35
            SEARCH_QUERIES["swiss"] = 14
            SEARCH_QUERIES["migros"] = 34
            SEARCH_QUERIES["investieren"] = 33
            SEARCH_QUERIES["\uD83E\uDD84"] = -1

            AUTOCOMPLETE_QUERIES["hyp"] = 0
            AUTOCOMPLETE_QUERIES["swi"] = 6
            AUTOCOMPLETE_QUERIES["mig"] = 1
            AUTOCOMPLETE_QUERIES["inv"] = 8
            AUTOCOMPLETE_QUERIES["bank"] = 1
            AUTOCOMPLETE_QUERIES["fond"] = 1
            AUTOCOMPLETE_QUERIES["welt"] = 4
            AUTOCOMPLETE_QUERIES["\uD83E\uDD84"] = -1

            SEARCH_DATA[LOAD_SITE_ID] = SEARCH_QUERIES // https://www.migrosbank.ch/de, https://blog.migrosbank.ch/de
            AUTOCOMPLETE_DATA[LOAD_SITE_ID] = AUTOCOMPLETE_QUERIES // https://www.migrosbank.ch/de, https://blog.migrosbank.ch/de
        }
    }
}
