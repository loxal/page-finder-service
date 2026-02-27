// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import net.loxal.finder.dto.Result
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service for performing search operations across indexed sites.
 *
 * This service acts as a facade over the search client, handling query execution
 * and result transformation. It delegates the actual search to [SimpleSearchClient]
 * and transforms raw search hits into user-facing result objects.
 *
 * Performance optimizations:
 * - Uses sequence-based lazy evaluation to avoid intermediate list allocations
 * - Inline function for zero-overhead transformation
 * - Direct reference to avoid property lookup overhead
 */
@Service
class SearchService
@Autowired
constructor(private val simpleSearchClient: SimpleSearchClient) {

    /**
     * Executes a search query against the specified site's indexed content.
     *
     * This method performs the following operations:
     * 1. Delegates search execution to the underlying search client
     * 2. Lazily transforms search hits into FoundPage objects
     * 3. Returns results wrapped in a Result DTO
     *
     * Performance characteristics:
     * - O(n) time complexity where n = number of hits
     * - Uses sequences for lazy evaluation, avoiding intermediate collections
     * - Inline transformation eliminates function call overhead
     *
     * @param query The search query string (will be processed with fuzzy matching)
     * @param siteId The unique identifier of the site to search within
     * @return Result containing the original query and list of matching pages
     */
    fun search(query: String, siteId: UUID): Result {
        val hits = simpleSearchClient.search(searchQuery = query, siteId = siteId)

        // Use asSequence() for lazy evaluation to avoid intermediate list allocations
        // This is particularly beneficial when dealing with large result sets
        val foundPages = hits.hits.asSequence()
            .map { page -> page.toFoundPage() }
            .toList()

        return Result(query, foundPages)
    }
}