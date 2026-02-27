// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.service

import net.loxal.finder.dto.Autocomplete
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service for providing autocomplete/search-as-you-type suggestions.
 *
 * This service delegates to [SimpleAutocompleteClient] for Elasticsearch queries
 * and transforms the results into user-facing autocomplete suggestions.
 *
 * Uses Elasticsearch's search-as-you-type functionality with n-gram tokenization
 * for prefix matching on indexed content.
 */
@Service
class AutocompleteService
@Autowired
constructor(private val autocompleteClient: SimpleAutocompleteClient) {

    /**
     * Retrieves autocomplete suggestions for a partial query string.
     *
     * This method:
     * 1. Queries Elasticsearch using multi-match with n-gram fields
     * 2. Extracts highlighted suggestions from search hits
     * 3. Returns up to 8 suggestions wrapped in an Autocomplete DTO
     *
     * Performance characteristics:
     * - O(1) for empty results (short-circuit)
     * - O(n) where n = number of suggestions (max 8)
     * - Direct list pass-through (no copying)
     *
     * @param query Partial search query entered by the user
     * @param siteId Site identifier to scope autocomplete suggestions
     * @return Autocomplete object containing list of suggestions (empty if no matches)
     */
    fun autocomplete(query: String, siteId: UUID): Autocomplete {
        val hits = autocompleteClient.search(query, siteId)

        // Direct pass-through - Autocomplete constructor handles empty list efficiently
        return Autocomplete(hits.autocompletes)
    }
}