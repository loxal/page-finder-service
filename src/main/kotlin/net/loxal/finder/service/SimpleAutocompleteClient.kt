// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import net.loxal.finder.dto.Hits
import net.loxal.finder.dto.HitsRoot
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
import java.util.*

/**
 * Elasticsearch client for autocomplete/search-as-you-type functionality.
 *
 * Uses Elasticsearch's multi_match query with bool_prefix type on n-gram fields
 * to provide real-time search suggestions as users type.
 *
 * Performance characteristics:
 * - Limits results to 8 suggestions for optimal UX
 * - Uses fragment_size of 30 characters for concise suggestions
 * - Leverages Elasticsearch's _2gram and _3gram fields for efficient prefix matching
 *
 * @see <a href="https://www.elastic.co/guide/en/elasticsearch/reference/7.4/search-as-you-type.html">
 *      Elasticsearch Search-as-you-type Documentation</a>
 */
@Repository
class SimpleAutocompleteClient {

    /**
     * Searches for autocomplete suggestions based on partial query input.
     *
     * This method:
     * 1. Builds an Elasticsearch multi_match query with n-gram fields
     * 2. Executes the search request with highlighting
     * 3. Extracts highlighted body text snippets from results
     * 4. Populates the autocomplete suggestions list
     *
     * Performance optimizations:
     * - Uses functional map for transformation (no explicit loops)
     * - Direct list manipulation instead of intermediate collections
     * - Limits results server-side to 8 items
     *
     * @param searchQuery Partial search query from user input
     * @param siteId Site identifier to scope search results
     * @return Hits object with autocomplete suggestions populated
     */
    fun search(searchQuery: String, siteId: UUID): Hits {
        val query = buildSearchQuery(searchQuery, siteId)
        val request = buildSearchRequest(query)

        val response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
        LOG.debug("searchQuery: {} - status: {} - body: {}", searchQuery, response.statusCode(), response.body())

        return parseAndEnrichResults(response.body())
    }

    /**
     * Builds the HTTP search request with appropriate headers.
     */
    private fun buildSearchRequest(query: String): HttpRequest {
        return HttpRequest.newBuilder()
            .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .uri(URI.create("$ELASTICSEARCH_SERVICE/$SITE_PAGE/_search"))
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build()
    }

    /**
     * Parses the Elasticsearch response and enriches hits with autocomplete suggestions.
     *
     * Extracts highlighted body snippets from each hit and adds them to the
     * autocompletes list for quick access.
     *
     * @param responseBody Raw JSON response from Elasticsearch
     * @return Hits object with populated autocomplete suggestions
     */
    private fun parseAndEnrichResults(responseBody: String): Hits {
        val hits = MAPPER.readValue(responseBody, HitsRoot::class.java).hits

        // Extract highlighted snippets and populate autocomplete list
        // Using mapNotNull to filter out any null/empty highlights
        val suggestions = hits.hits.mapNotNull { page ->
            page.highlight[Fields.BODY]?.firstOrNull()?.takeIf { it.isNotBlank() }
        }

        hits.autocompletes.addAll(suggestions)

        return hits
    }

    /**
     * Builds an Elasticsearch query for autocomplete functionality.
     *
     * Query structure:
     * - Uses multi_match with bool_prefix type for prefix matching
     * - Searches across body, body._2gram, and body._3gram fields
     * - Filters results by siteId for multi-tenancy
     * - Highlights matching fragments with custom HTML tags
     * - Limits to 8 results with 30-character fragments
     *
     * @param searchQuery The partial query string
     * @param siteId Site identifier for filtering
     * @return JSON query string for Elasticsearch
     */
    private fun buildSearchQuery(searchQuery: String, siteId: UUID): String {
        return """
            {
                "query": {
                    "bool": {
                        "must": {
                            "multi_match": {
                                "fields": ["body", "body._2gram", "body._3gram"],
                                "type": "bool_prefix",
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
                    "fragment_size": 30,
                    "fields": {
                        "body": {}
                    }
                },
                "size": $MAX_AUTOCOMPLETE_RESULTS
            }
        """.trimIndent()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SimpleAutocompleteClient::class.java)

        /**
         * Maximum number of autocomplete suggestions to return.
         * Limited to 8 for optimal user experience - balances suggestion diversity
         * with UI space and cognitive load.
         */
        private const val MAX_AUTOCOMPLETE_RESULTS = 8

        /**
         * Fragment size for highlighted suggestions (in characters).
         * 30 characters provides enough context for meaningful suggestions
         * without overwhelming the user.
         */
        private const val FRAGMENT_SIZE = 30
    }
}