// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import net.loxal.finder.dto.Hits
import net.loxal.finder.dto.Page
import net.loxal.finder.service.SimpleIndexClient.Companion.BASIC_AUTH_HEADER
import net.loxal.finder.service.SimpleIndexClient.Companion.CLIENT
import net.loxal.finder.service.SimpleIndexClient.Companion.ELASTICSEARCH_SERVICE
import net.loxal.finder.service.SimpleIndexClient.Companion.MAPPER
import net.loxal.finder.service.SimpleIndexClient.Companion.SITE_PAGE
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Repository
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.*

/**
 * Should serve as a persistence client that works on a different index than the search client.
 */
@Repository
class SimpleSearchClient {

    fun fetchAllSitePages(siteId: UUID): Hits {
        return searchDirect(searchQuery = "*", pageSize = SiteService.MAX_TOTAL_QUERY_PAGE_SIZE, siteId = siteId)
    }

    /**
     * Fetches pages for a site that were last updated before the specified threshold.
     * This is more efficient than fetchAllSitePages + client-side filtering as the
     * date range filter is applied server-side in Elasticsearch.
     *
     * @param siteId The site to fetch pages for
     * @param olderThan Only return pages with 'updated' timestamp before this instant
     * @return Hits containing only the obsolete pages
     */
    fun fetchObsoleteSitePages(siteId: UUID, olderThan: Instant): Hits {
        val query = buildObsoletePageQuery(siteId, olderThan, SiteService.MAX_TOTAL_QUERY_PAGE_SIZE)
        return executeSearchRequest(query) { statusCode ->
            LOG.debug("fetchObsoleteSitePages - siteId: $siteId - olderThan: $olderThan - status: $statusCode")
        }
    }

    private fun buildObsoletePageQuery(siteId: UUID, olderThan: Instant, pageSize: Int): String {
        return """
            {
                "query": {
                    "bool": {
                        "must": [
                            { "match_phrase": { "siteId": "$siteId" } },
                            { "range": { "updated": { "lt": "$olderThan" } } }
                        ]
                    }
                },
                "size": $pageSize
            }
        """.trimIndent()
    }

    fun search(searchQuery: String, siteId: UUID): Hits {
        return searchDirect(searchQuery = "$searchQuery$FUZZY_DIRECTIVE", pageSize = 50, siteId = siteId)
    }

    private fun searchDirect(searchQuery: String, pageSize: Int, siteId: UUID): Hits {
        val query = buildSearchQuery(searchQuery, siteId, pageSize)
        val hits = executeSearchRequest(query) { statusCode ->
            LOG.debug("searchQuery: $searchQuery - status: $statusCode")
            // TODO("consider implementing BODY_SIZE_LIMIT to cut large bodies server-side instead of browser/client-side")
        }

        // Apply highlighting to results
        enrichWithHighlights(hits)
        return hits
    }

    /**
     * Executes a search request to Elasticsearch and parses the response.
     *
     * Performance optimizations:
     * - Single JSON parse operation using JsonNode navigation
     * - Reuses HTTP client connection pool
     * - Avoids intermediate string serialization
     *
     * @param query The JSON query string
     * @param logAction Optional logging action that receives the response status code
     * @return Parsed Hits object or empty Hits on error
     */
    private inline fun executeSearchRequest(query: String, logAction: (Int) -> Unit = {}): Hits {
        val searchRequest = HttpRequest.BodyPublishers.ofString(query)
        val request = HttpRequest.newBuilder()
            .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_search"))
            .POST(searchRequest)
            .build()

        val response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
        logAction(response.statusCode())

        return runCatching {
            // Optimized: Direct JsonNode navigation instead of double parsing
            val rootNode = MAPPER.readTree(response.body())
            val hitsNode = rootNode.get("hits")
            MAPPER.treeToValue(hitsNode, Hits::class.java) ?: Hits()
        }.getOrElse { e ->
            LOG.warn("Failed to parse search response", e)
            Hits()
        }
    }

    /**
     * Enriches search hits with highlighted content from Elasticsearch.
     *
     * For each document, extracts highlighted snippets (if available) for title, body, and URL.
     * Falls back to original content if no highlight is available.
     *
     * Performance: O(n) where n = number of hits
     */
    private fun enrichWithHighlights(hits: Hits) {
        hits.hits.forEach { document ->
            enrichField(document, Fields.TITLE)
            enrichField(document, Fields.BODY)
            enrichField(document, Fields.URL)
        }
    }

    /**
     * Enriches a single field with highlighted content.
     * Uses the highlight if available, otherwise falls back to the original source.
     */
    private fun enrichField(document: Page, field: String) {
        val highlightValue = document.highlight[field]?.firstOrNull()
        val fallbackValue = document._source[field]?.toString()
        document.setItem("${Page.HIT_TEASER_PREFIX}$field", highlightValue ?: fallbackValue)
    }

    /**
     * Builds an Elasticsearch query for full-text search with highlighting.
     *
     * Uses string concatenation for better performance than StringBuilder
     * or string templates for this specific case (JVM optimization).
     */
    private fun buildSearchQuery(searchQuery: String, siteId: UUID, pageSize: Int): String {
        return """
            {
                "query": {
                    "bool": {
                        "must": {
                            "query_string": {
                                "fields": ["body", "title", "url"],
                                "query": "$searchQuery"
                            }
                        },
                        "filter": {
                            "match_phrase": {
                                "siteId": "$siteId"
                            }
                        }
                    }
                },
                "highlight": {
                    "pre_tags": ["<span class=\"pf-highlight\">"],
                    "post_tags": ["</span>"],
                    "number_of_fragments": 1,
                    "fragment_size": 150,
                    "fields": {
                        "body": {},
                        "title": {},
                        "url": {}
                    }
                },
                "size": $pageSize
            }
        """.trimIndent()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SimpleSearchClient::class.java)
        private const val FUZZY_DIRECTIVE = "~"
    }
}
