// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import net.loxal.finder.dto.*
import net.loxal.finder.service.SiteService.Companion.CRAWL_STATUS_SINGLETON_DOCUMENT
import net.loxal.finder.service.SiteService.Companion.SITE_CONFIGURATION_DOCUMENT_PREFIX
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Repository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.function.Consumer

/**
 * Should serve as a persistence client that works on a different index than the search client.
 */
@Repository
class SimpleIndexClient {

    fun indexSiteProfile(siteProfile: SiteProfile): HttpResponse<String> {
        val call: HttpRequest = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PROFILE/_doc/$SITE_CONFIGURATION_DOCUMENT_PREFIX${siteProfile.id}"))
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(siteProfile)))
                .build()

        val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
        LOG.debug("status: ${response.statusCode()}")
        return response
    }

    fun indexCrawlStatus(sitesCrawlStatus: SitesCrawlStatus): HttpResponse<String> {
        sitesCrawlStatus.sites.forEach(Consumer { it ->
            it.siteProfile?.configs?.forEach {
                LOG.info("${it.url} - ${it.allowUrlWithQuery} - ${it.sitemapsOnly} - ${it.pageBodyCssSelectorOrDefault()}")
            }
        })

        return try {
            val call: HttpRequest = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$SVC_SINGLETONS/_doc/$CRAWL_STATUS_SINGLETON_DOCUMENT"))
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(sitesCrawlStatus)))
                .timeout(Duration.ofSeconds(30))
                .build()
            val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
            LOG.debug("Elasticsearch indexCrawlStatus response status: ${response.statusCode()}")
            response
        } catch (e: Exception) {
            LOG.error("Failed to index crawl status to Elasticsearch: ${e.message}", e)
            throw e
        }
    }

    fun indexPage(page: FetchedPage): HttpResponse<String> {
        val call: HttpRequest = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_doc/${page.id}"))
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(page)))
                .build()
        val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
        LOG.debug("status: ${response.statusCode()}")

        return response
    }

    fun index(indexType: String, page: Page): HttpResponse<String> {
        val call: HttpRequest = HttpRequest.newBuilder()
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .uri(URI.create("$ELASTICSEARCH_SERVICE/$indexType/_doc/${page._id}"))
                        .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(page._source)))
                    .build()
        val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
        LOG.debug("status: ${response.statusCode()}")
        return response
    }

    fun fetchPage(pageId: String): FetchedPage? {
        val response = fetchDoc(SITE_PAGE, pageId)
        if (HttpStatus.OK.value() != response.statusCode()) return null

        val page = MAPPER.readValue(response.body(), PageRoot::class.java)
        return page._source
    }

    fun fetch(indexType: String, docId: String): List<Page> {
        val response = fetchDoc(indexType, docId)
        if (HttpStatus.OK.value() != response.statusCode()) return emptyList()

        val doc = MAPPER.readValue(response.body(), Page::class.java)
        return listOf(doc)
    }

    fun fetchSiteCrawlStatus(): SitesCrawlStatus {
        val response = fetchDoc(SVC_SINGLETONS, CRAWL_STATUS_SINGLETON_DOCUMENT)
        val crawlStatus = response.body()
        val sitesCrawlStatusRoot = MAPPER.readValue(crawlStatus, SitesCrawlStatusRoot::class.java)
        return sitesCrawlStatusRoot._source!!
    }

    fun fetchSiteProfile(siteId: UUID): SiteProfile? {
        val response = fetchDoc(SITE_PROFILE, "$SITE_CONFIGURATION_DOCUMENT_PREFIX$siteId")
        if (HttpStatus.OK.value() != response.statusCode()) return null

        val siteProfileRoot = MAPPER.readValue(response.body(), SiteProfileRoot::class.java)
        return siteProfileRoot._source
    }

    private fun fetchDoc(indexType: String, docId: String): HttpResponse<String> {
        val call = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$indexType/_doc/$docId"))
                .GET()
                .build()
        val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
        LOG.debug("status: ${response.statusCode()}")
        return response
    }

    fun deleteAllSitePages(siteId: UUID): DeletePagesResult {
        val call = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_delete_by_query"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":{\"match\":{\"siteId\":\"$siteId\"}}}"))
                .build()

        return try {
            val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
            LOG.debug("status: ${response.statusCode()} - response.body: ${response.body()}")

            if (response.statusCode() in 200..299) {
                parseDeleteByQueryResponse(response.body(), -1) // -1 indicates unknown total for "delete all"
            } else {
                LOG.error("Delete all pages failed with status ${response.statusCode()}: ${response.body()}")
                DeletePagesResult(
                    deleted = 0,
                    failed = 0,
                    total = 0,
                    success = false,
                    errorMessage = "HTTP ${response.statusCode()}: ${response.body()}"
                )
            }
        } catch (e: Exception) {
            LOG.error("Exception during delete all pages operation: ${e.message}", e)
            DeletePagesResult(
                deleted = 0,
                failed = 0,
                total = 0,
                success = false,
                errorMessage = e.message
            )
        }
    }

    /**
     * Deletes pages for a site that were last updated before the specified threshold.
     * This is the most efficient approach as it performs a single delete-by-query
     * operation directly in Elasticsearch, without needing to fetch document IDs first.
     *
     * @param siteId The site to delete obsolete pages for
     * @param olderThan Only delete pages with 'updated' timestamp before this instant
     * @return DeletePagesResult with counts of deleted/failed documents
     */
    fun deleteObsoleteSitePages(siteId: UUID, olderThan: Instant): DeletePagesResult {
        val query = """
            {
                "query": {
                    "bool": {
                        "must": [
                            { "match_phrase": { "siteId": "$siteId" } },
                            { "range": { "updated": { "lt": "$olderThan" } } }
                        ]
                    }
                }
            }
        """.trimIndent()

        val call = HttpRequest.newBuilder()
            .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_delete_by_query"))
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build()

        return try {
            val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
            LOG.debug("deleteObsoleteSitePages - siteId: $siteId - olderThan: $olderThan - status: ${response.statusCode()}")

            if (response.statusCode() in 200..299) {
                parseDeleteByQueryResponse(response.body(), -1)
            } else {
                LOG.error("Delete obsolete pages failed with status ${response.statusCode()}: ${response.body()}")
                DeletePagesResult(
                    deleted = 0,
                    failed = 0,
                    total = 0,
                    success = false,
                    errorMessage = "HTTP ${response.statusCode()}: ${response.body()}"
                )
            }
        } catch (e: Exception) {
            LOG.error("Exception during delete obsolete pages operation: ${e.message}", e)
            DeletePagesResult(
                deleted = 0,
                failed = 0,
                total = 0,
                success = false,
                errorMessage = e.message
            )
        }
    }

    fun deletePages(docIds: List<String>): DeletePagesResult {
        if (docIds.isEmpty()) {
            return DeletePagesResult(deleted = 0, failed = 0, total = 0, success = true)
        }

        // Use joinToString for efficient JSON array building (single pass, no intermediate strings)
        val docIdsJson = docIds.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        val query = """{"query":{"terms":{"_id":$docIdsJson}}}"""

        val call = HttpRequest.newBuilder()
                .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_delete_by_query"))
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build()

        return try {
            val response = CLIENT.send(call, HttpResponse.BodyHandlers.ofString())
            LOG.debug("status: ${response.statusCode()} - docIds: ${docIds.size}")

            if (response.statusCode() in 200..299) {
                parseDeleteByQueryResponse(response.body(), docIds.size)
            } else {
                LOG.error("Delete request failed with status ${response.statusCode()}: ${response.body()}")
                DeletePagesResult(
                    deleted = 0,
                    failed = docIds.size,
                    total = docIds.size,
                    success = false,
                    errorMessage = "HTTP ${response.statusCode()}: ${response.body()}"
                )
            }
        } catch (e: Exception) {
            LOG.error("Exception during delete operation: ${e.message}", e)
            DeletePagesResult(
                deleted = 0,
                failed = docIds.size,
                total = docIds.size,
                success = false,
                errorMessage = e.message
            )
        }
    }

    private fun parseDeleteByQueryResponse(responseBody: String, requestedCount: Int): DeletePagesResult {
        return try {
            val jsonNode = MAPPER.readTree(responseBody)
            val deleted = jsonNode.path("deleted").asInt(0)
            val total = jsonNode.path("total").asInt(0)
            val failures = jsonNode.path("failures")
            val failedCount = if (failures.isArray) failures.size() else 0

            // Use response total if requestedCount is unknown (-1), otherwise use requestedCount
            val effectiveTotal = if (requestedCount < 0) total else requestedCount

            DeletePagesResult(
                deleted = deleted,
                failed = failedCount,
                total = effectiveTotal,
                success = failedCount == 0,
                errorMessage = if (failedCount > 0) "Some documents failed to delete" else null
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse delete response, assuming success: ${e.message}")
            // For unknown count, use 0 as fallback since we can't determine actual numbers
            val fallbackCount = if (requestedCount < 0) 0 else requestedCount
            DeletePagesResult(
                deleted = fallbackCount,
                failed = 0,
                total = fallbackCount,
                success = true
            )
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SimpleIndexClient::class.java)
        private val credentials = ("elastic:${SiteService.ADMIN_SITE_SECRET}").toByteArray()
        internal val ELASTICSEARCH_SERVICE = if (System.getenv("ELASTICSEARCH_SERVICE") == null) "http://elasticsearch:9200" else System.getenv("ELASTICSEARCH_SERVICE")
        internal val CLIENT = HttpClient.newHttpClient()
        internal val BASIC_AUTH_HEADER = "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        internal val MAPPER = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()

        internal const val SVC_SINGLETONS = "svc-singletons"
        internal const val SITE_PAGE = "site-page"
        internal const val SITE_PROFILE = "site-profile"
    }
}
