// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import net.loxal.finder.service.Fields
import net.loxal.finder.service.SiteService

/**
 * Represents an Elasticsearch document for a page.
 *
 * Performance optimizations:
 * - Pre-computed field keys avoid string concatenation in hot paths
 * - Lazy label parsing with cached delimiter
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class Page {
    @JsonProperty("_id")
    lateinit var _id: String

    @JsonProperty("_source")
    var _source: MutableMap<String, Any?> = mutableMapOf()

    @JsonProperty("highlight")
    var highlight: Map<String, List<String>> = mutableMapOf()

    private constructor()

    constructor(_id: String) {
        this._id = _id
    }

    operator fun get(key: String): String {
        return _source[key]?.toString() ?: ""
    }

    fun getLabels(key: String): List<String> {
        return when (val value = _source[key]) {
            null -> emptyList()
            is List<*> -> value.filterIsInstance<String>().filter { it.isNotEmpty() }
            is String -> if (value.isEmpty()) emptyList() else value.split(LABEL_DELIMITER)
            else -> {
                val str = value.toString()
                if (str.isEmpty() || str == "[]") emptyList() else str.split(LABEL_DELIMITER)
            }
        }
    }

    fun setLabels(key: String, values: List<String>) {
        _source[key] = values.joinToString { value -> value.trim() }
    }

    fun setItem(key: String, value: String?) {
        addItem(key, value ?: "")
    }

    private fun addItem(key: String, value: String) {
        val trimmedValue = value.trim()
        _source[key] = trimmedValue
    }

    /**
     * Converts this page to a FoundPage DTO for search results.
     * Uses pre-computed field keys for optimal performance.
     */
    fun toFoundPage(): FoundPage {
        return FoundPage(
                title = this[HIT_TEASER_TITLE],
                body = this[HIT_TEASER_BODY],
                url = this[HIT_TEASER_URL],
                urlRaw = this[Fields.URL],
                labels = getLabels(SiteService.PAGE_LABELS),
                thumbnail = this[SiteService.PAGE_THUMBNAIL]
        )
    }

    override fun hashCode(): Int {
        return _id.hashCode()
    }

    override fun toString(): String {
        return "$_id:$_source"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Page

        if (_id != other._id) return false
        if (_source != other._source) return false
        if (highlight != other.highlight) return false

        return true
    }

    companion object {
        internal const val HIT_TEASER_PREFIX = "hit.teaser."

        // Pre-computed field keys to avoid string concatenation in hot paths
        private const val HIT_TEASER_TITLE = HIT_TEASER_PREFIX + Fields.TITLE
        private const val HIT_TEASER_BODY = HIT_TEASER_PREFIX + Fields.BODY
        private const val HIT_TEASER_URL = HIT_TEASER_PREFIX + Fields.URL

        // Cached delimiter for label parsing
        private const val LABEL_DELIMITER = ", "
    }
}