// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class SiteProfile(
        var id: UUID = UUID.fromString("0-0-0-1-0"),
        var secret: UUID = UUID.fromString("0-0-0-2-0"),
        var email: String = "",
        var configs: List<Config> = emptyList()
) {

    /**
     * Configuration for a site crawl.
     *
     * Jackson deserialization is handled via default parameter values in the data class constructor.
     * The Kotlin Jackson module properly handles data classes with default values.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Config(
        @JsonProperty("url") var url: URI? = null,
        @JsonProperty("pageBodyCssSelector") var pageBodyCssSelector: String = DEFAULT_PAGE_BODY_CSS_SELECTOR,
        @JsonProperty("sitemapsOnly") var sitemapsOnly: Boolean = false,
        @JsonProperty("allowUrlWithQuery") var allowUrlWithQuery: Boolean = false
    ) {
        /**
         * Returns the CSS selector for extracting page body content.
         * Returns the default "body" selector if empty.
         */
        fun pageBodyCssSelectorOrDefault(): String = pageBodyCssSelector.ifEmpty { DEFAULT_PAGE_BODY_CSS_SELECTOR }

        companion object {
            const val DEFAULT_PAGE_BODY_CSS_SELECTOR = "body"
        }
    }
}
